(ns mongologic.history
  (:require [mongologic.core :as mongologic]
            [clj-time.core :as clj-time]
            [taoensso.timbre :as log]))


;; How to implement Document Versioning with Couchbase
;; http://tugdualgrall.blogspot.com.es/2013/07/how-to-implement-document-versioning.html
;; https://github.com/tgrall/couchbase-how-to-versioning/blob/master/src/main/java/com/couchbase/plugin/client/CouchbaseClientWithVersioning.java

;; Django
;; Applications that enable versioning of model content
;; https://www.djangopackages.com/grids/g/versioning/
;; Reversion
;; https://github.com/etianen/django-reversion

;; Vermongo: Simple Document Versioning with MongoDB
;; https://github.com/thiloplanz/v7files/wiki/Vermongo


;; Eventually, everything could be specified through a :history entry in the
;; model's entity map. This would contain another map that could specify the
;; history collection, which fields to save, etc. Then, there would be no
;; need to specify a :before-update and :on-update-errors callbacks, as the
;; corresponding functionality would be built-in and automatically handled in
;; Mongologic.


;; Any name can be specified for the history collection.
;; A way to name these collections is to use the same name as the original
;; collection but with the `.history` suffix (ex. articles.history).
;;
;; [1] "Period characters can occur in collection names, so that
;; acme.user.history is a valid namespace, with acme as the database name,
;; and user.history as the collection name."
;; http://docs.mongodb.org/manual/faq/developers/#faq-dev-namespace
;; [2] Subcollections, p. 10 of Chodorow's "MongoDB: The Definitive Guide"
;; (2nd Edition)


; save-revision (borrowing the term from https://www.jboss.org/envers)
; save-version (borrowing the term from https://github.com/airblade/paper_trail)
; create
(defn save
  "Saves the specified record in the history.

  Returns:
   - nil if the record was not found
   - the created history record if all is ok"
  [{database :database
    {:keys [history-collection]} :entity :as model-component}
   record-id]
  (log/info "mongologic.history/save called for " record-id)
  (when-let [{:keys [_id updated_at] :as record}
               (mongologic/find-by-id model-component record-id)]
    ;
    ; We'll rely on MongoDB's automatic indexing of the _id field for fast
    ; queries to retrieve a specific version (or all versions) of a document/
    ; record (see `find-record-at` below).
    ; http://docs.mongodb.org/manual/core/index-single/#index-0
    ;
    ; Notice though that the _id index is not a compound index, but an index
    ; on the whole value of the _id field. MongoDB uses the raw BSON
    ; representation of the field to make comparisons. So {:a "a", :b "b"}
    ; does not equal {:b "b", :a "a"}.
    ; http://stackoverflow.com/q/7246434
    ;
    ; That's clear when querying the compound _id field with its components
    ; in an order different than the order in which they were created. For
    ; example, if a record has been created with
    ;
    ; {:_id
    ;   (array-map
    ;     :_id (mongologic/to-object-id "52c86e4803642e240d717729")
    ;     :updated_at (clj-time/date-time 2014 1 4 20 28 12 82))}
    ;
    ; then the following query will not find anything
    ;
    ; (mongologic/find
    ;   {:collection :articles.history}
    ;   {:where
    ;     {:_id
    ;       (array-map
    ;         :updated_at (clj-time/date-time 2014 1 4 20 28 12 82)
    ;         :_id (mongologic/to-object-id "52c86e4803642e240d717729"))}})
    ;
    ; To ensure the order of the _id field component values, they are
    ; specified using an `array-map`.
    ; http://clojure.org/data_structures#Data Structures-ArrayMaps
    ; The order of this Clojure array-map is preserved all the way through to
    ; MongoDB because...
    ;
    ; Mongologic's `create` uses CongoMongo's `insert!` [1], which uses
    ; `somnium.congomongo.coerce/coerce` [2] to map the data to insert,
    ; specified as a Clojure data structure, to MongoDB's Java driver calls.
    ; This mapping is done in the implementation of the `ConvertibleToMongo`
    ; protocol for `IPersistentMap` [3], which uses `doseq` to loop through
    ; the array-map, adding each element to a `com.mongodb.BasicDBObject`
    ; [4]. Eliot Horowitz (MongoDB CTO) says that "BasicDBObject is ordered"
    ; [5], but let's check it...
    ; BasicDBOject inherits from `LinkedHashMap` and the docs for this say...
    ; [6]
    ; > Hash table and linked list implementation of the Map interface, with
    ; > predictable iteration order. This implementation differs from HashMap
    ; > in that it maintains a doubly-linked list running through all of its
    ; > entries. This linked list defines the iteration ordering, which is
    ; > NORMALLY the order in which keys were inserted into the map
    ; > (insertion-order).
    ; (the uppercase emphasis is mine)
    ;
    ; It says "normally" just because that's the default
    ; > A special constructor is provided to create a linked hash map whose
    ; > order of iteration is the order in which its entries were last
    ; > accessed
    ;
    ; So, yes, BasicDBObject keeps the order of its elements as they were
    ; inserted, and so the order of the `array-map` is preserved all the way
    ; through to MongoDB.
    ;
    ; [1] https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo.clj
    ; [2] https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo/coerce.clj
    ; [3] array-map implements IPersistentMap...
    ; (instance? clojure.lang.IPersistentMap (array-map :a 2))
    ; ; => true
    ; [4] http://api.mongodb.org/java/current/com/mongodb/BasicDBObject.html
    ; [5] https://groups.google.com/forum/#!topic/mongodb-user/vqXYxVMA_0Q
    ; [6] http://docs.oracle.com/javase/7/docs/api/java/util/LinkedHashMap.html
    (mongologic/create {:database database
                        :entity {:collection history-collection}}
                       (assoc record
                              :_id
                              (array-map :_id _id :updated_at updated_at)))))


(defn delete
  "#TODO Should accept a component
  Deletes the current version of the specified record from the history. This
  is meant to be used to clean up history after a failed update, as otherwise
  the latest version of a record would be in the history."
  [{:keys [history-collection] :as entity} record-id]
  (let [{:keys [_id updated_at] :as record}
          (mongologic/find-by-id entity record-id)]
    (mongologic/delete {:collection history-collection}
                       {:_id _id :updated_at updated_at})))




 ; (PaperTrail equivalent method is named `record_destroy`
 ; https://github.com/airblade/paper_trail/blob/master/lib/paper_trail/has_paper_trail.rb
 ; Mongoid History is named `track_destroy`
 ; https://github.com/aq1018/mongoid-history/blob/master/lib/mongoid/history/trackable.rb
 ;
 ; "Thunderbird saves the delete action in a local file"
 ; http://forums.mozillazine.org/viewtopic.php?f=39&t=620871
 ; )
(defn save-delete
  "Saves the delete of the specified record in the history."
  [{database :database
    {:keys [history-collection]} :entity
    :as model-component}
   record-id]
  ;; https://github.com/thiloplanz/v7files/wiki/Vermongo#deleting-a-document
  ;; https://github.com/thiloplanz/v7files/wiki/Vermongo#example
  (when (save model-component record-id)
    (let [deleted-at (clj-time/now)]
      (mongologic/create {:database database
                          :entity {:collection history-collection}}
                         {:_id (array-map :_id record-id
                                          :updated_at deleted-at)
                          ; :created_at and updated_at, if not specified,
                          ; would be automatically added by Mongologic, and
                          ; might not be equal to :deleted_at and
                          ; [:_id :updated_at], which might be confusing
                          :created_at deleted-at
                          :updated_at deleted-at
                          :deleted_at deleted-at}))))

;; MongoDB records vs documents
;; A document-oriented database replaces the concept of a "row" with a more
;; flexible model, the "document." By allowing embedded documents and arrays,
;; the document- oriented approach makes it possible to represent complex
;; hierarchical relationships with a single record
;; Chodorow's "MongoDB: The Definitive Guide"
;;
;; document:
;; A record in a MongoDB collection and the basic unit of data in MongoDB.
;; http://docs.mongodb.org/manual/reference/glossary/#term-document

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
  (log/info "find-latest-matching-record-at " model-component id datetime conditions)
  (let [record
          (mongologic/find-one model-component
                               {:$and [{:_id id} conditions]})]
    (if (and record (not (clj-time/after? (:updated_at record) datetime)))
        record
        ; Despite the history collections _id's being compound fields, the
        ; index that MongoDB creates automatically on _id will not be a
        ; compound index
        ; http://stackoverflow.com/q/18388434
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
          ; Because of how $lte is used against the compound :_id field in
          ; the above query, it may be that the obtained record is not a
          ; version of requested record. This means that actually there are
          ; no matching records, actually.
          (when (= matching-record-id id)
            (assoc matching-record :_id matching-record-id))))))

;;
;; Reference:
;; In the PaperTrail API this returns the widget (not a version) as it looked
;; at the given timestamp...
;;   widget.version_at(timestamp)
;; https://github.com/airblade/paper_trail#api-summary
;;
(defn find-record-at  ;; find-flashcard-at / find-document-at
  "Returns:
    - the specified record as it was at the specified time, or
    - a map with a :deleted_at key if the record was deleted by the
      specified time, or
    - nil if a record with the given id is not found anywhere,
      including history (i.e. if the record has never existed)"
  [model-component id datetime]
  (log/debug "find-record-at " model-component id datetime)
  (find-latest-matching-record-at model-component id datetime {}))




;; Getting the history of a record
;;
;; #TODO Find an appropriate name...
;; history/find-record-history ?
;; history/find-record-versions ?
;; history/find-by-record-id ?
;;
;; Usage:
;;   (mongologic.history/find-all-by-record-id model-component
;;                                             "53c25fd403645ee23502dd64")
(defn find-all-by-record-id
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
  ;; That's why the "dot notation" is used, which allows to match all documents
  ;; where the value of the field :_id is an embedded document that contains a
  ;; field :_id with the specified value and may contain other fields.
  ;;
  ;; http://docs.mongodb.org/manual/tutorial/query-documents/#embedded-documents
  (mongologic/find {:database database
                     :entity {:collection history-collection}}
                   {:where {:_id._id (mongologic/to-object-id id)}
                    :sort {:_id -1}}))
