(ns mongologic.history
  (:require [mongologic.core :as mongologic]
            [clj-time.core :as clj-time]
            [taoensso.timbre :as log]))


(defn save
  "Saves the specified record in the history.

  Returns:
  - [false nil] if the record was not found
  - [false {:base [:insert-error]}] if insertion into the database fails
  - [true created-history-record] otherwise"
  [{database :database
    {:keys [history-collection]} :entity
    :as model-component}
   record-id]
  (if-let [{:keys [_id updated_at] :as record}
             (mongologic/find-by-id model-component record-id)]
    ;; Relies on MongoDB's automatic indexing of the _id field for fast
    ;; queries to retrieve a specific version (or all versions) of a
    ;; document/record (see `find-record-at` below).
    ;; http://docs.mongodb.org/manual/core/index-single/#index-0
    ;;
    ;; Notice though that the _id index is not a compound index, but an index
    ;; on the whole value of the _id field. MongoDB uses the raw BSON
    ;; representation of the field to make comparisons. So {:a "a", :b "b"}
    ;; does not equal {:b "b", :a "a"}.
    ;; http://stackoverflow.com/q/7246434
    ;;
    ;; That's clear when querying the compound _id field with its components
    ;; in an order different than the order in which they were created. For
    ;; example, if a record has been created with
    ;;
    ;; {:_id
    ;;   (array-map
    ;;     :_id (mongologic/to-object-id "52c86e4803642e240d717729")
    ;;     :updated_at (clj-time/date-time 2014 1 4 20 28 12 82))}
    ;;
    ;; then the following query will not find anything
    ;;
    ;; (mongologic/find
    ;;   {:collection :articles.history}
    ;;   {:where
    ;;     {:_id
    ;;       (array-map
    ;;         :updated_at (clj-time/date-time 2014 1 4 20 28 12 82)
    ;;         :_id (mongologic/to-object-id "52c86e4803642e240d717729"))}})
    ;;
    ;; To ensure the order of the _id field component values, they are
    ;; specified using an `array-map`.
    ;; http://clojure.org/data_structures#Data Structures-ArrayMaps
    (mongologic/create {:database database
                        :entity {:collection history-collection}}
                       (assoc record
                              :_id
                              (array-map :_id _id :updated_at updated_at)))
    [false nil]))


(defn delete
  "Deletes the current version of the specified record from the history. This
  is meant to be used to clean up history after a failed update, as otherwise
  the latest version of a record would be in the history.

  Returns:
  - the number of records deleted"
  [{database :database
    {:keys [history-collection]} :entity
    :as model-component}
   record-id]
  (let [{:keys [_id updated_at] :as record}
          (mongologic/find-by-id model-component record-id)]
    (mongologic/delete {:database database
                        :entity {:collection history-collection}}
                       {:_id _id :updated_at updated_at})))


(defn save-delete
  "Saves the delete of the specified record in the history.

  Two history records are created, one stores the record that is going to be
  deleted, with the original :created_at and :updated_at values, the other
  essentially stores the time of the deletion in a :deleted_at field.

  Returns:
  - [false nil] if the record was not found
  - [false {:base [:insert-error]}] if insertion into the database fails
  - [true saved-record] otherwise"
  [{database :database
    {:keys [history-collection]} :entity
    :as model-component}
   record-id]
  (let [result (save model-component record-id)]
    (if (first result)
      (let [deleted-at (clj-time/now)]
        (mongologic/create {:database database
                            :entity {:collection history-collection}}
                           {:_id (array-map :_id record-id
                                            :updated_at deleted-at)
                            ;; :created_at and updated_at, if not specified,
                            ;; would be automatically added by Mongologic, and
                            ;; might not be equal to :deleted_at and
                            ;; [:_id :updated_at], which might be confusing
                            :created_at deleted-at
                            :updated_at deleted-at
                            :deleted_at deleted-at}))
      result)))


(defn find-latest-matching-record-at
  "Returns:
  - what was at `datetime` the latest version of the record specified by
    `id` that matched the specified `conditions`, or
  - nil if a record with the given id is not found anywhere,
    including history (i.e. if the record has never existed)"
  [{database :database
    {:keys [history-collection]} :entity
    :as model-component}
   id
   datetime
   conditions]
  (let [record
          (mongologic/find-one model-component
                               {:$and [{:_id id} conditions]})]
    (if (and record (not (clj-time/after? (:updated_at record) datetime)))
        record
        ;; Despite the history collections _id's being compound fields, the
        ;; index that MongoDB creates automatically on _id will not be a
        ;; compound index
        ;; http://stackoverflow.com/q/18388434
        (let [matching-record
                (first
                  (mongologic/find
                    {:database database
                     :entity {:collection history-collection}}
                    {:where
                       {:$and
                          [{:_id {:$lte (array-map :_id id
                                                   :updated_at datetime)}}
                           conditions]}
                     :sort {:_id -1}
                     :limit 1}))
              matching-record-id
                (get-in matching-record [:_id :_id])]
          ;; Because of how $lte is used against the compound :_id field in
          ;; the above query, it may be that the obtained record is not a
          ;; version of requested record. This means that actually there are
          ;; no matching records, actually.
          (when (= matching-record-id id)
            (assoc matching-record :_id matching-record-id))))))


(defn find-record-at
  "Returns:
  - the specified record as it was at the specified time, or
  - a map with a :deleted_at key if the record was deleted by the
    specified time, or
  - nil if a record with the given id is not found anywhere,
    including history (i.e. if the record has never existed)"
  [model-component id datetime]
  (find-latest-matching-record-at model-component id datetime {}))


(defn find-all-by-record-id
  "Returns the history of the specified record."
  [{database :database
    {:keys [history-collection]} :entity
    :as model-component}
   id]
  ;; If this :where were used...
  ;;   :where {:_id {:_id id}}
  ;; no documents would be returned because this {:_id id} is interpreted as
  ;; the whole (embedded) document to match, and there are no history records
  ;; whose value for the _id field is an embedded document with just the :_id
  ;; field (all have also an :updated_at field).
  ;;
  ;; That's why the "dot notation" is used, which allows to match all
  ;; documents where the value of the field :_id is an embedded document that
  ;; contains a field :_id with the specified value and may contain other
  ;; fields.
  ;;
  ;; http://docs.mongodb.org/manual/tutorial/query-documents/#embedded-documents
  (mongologic/find {:database database
                    :entity {:collection history-collection}}
                   {:where {:_id._id (mongologic/to-object-id id)}
                    :sort {:_id -1}}))
