(ns mongologic.core
  (:refer-clojure :exclude [find count update])
  (:require [somnium.congomongo :as db]
            [clj-time.core :as time]
            [taoensso.timbre :as log])
  (:load "coerce"))


(declare to-object-id)


(defn create
  "These callbacks will be called, in the order listed here, if defined in
  the map under the :entity key of the `model-component` parameter:

  - :before-validation
  - :before-validation-on-create
  - :validator
  - :before-save
  - :before-create
  - :after-create

  All callbacks will be passed the model-component and the attributes map.
  The :validator callback must return a collection of errors (empty or nil if
  no errors). The other callbacks must return the possibly updated attributes
  map.

  Returns:
    - [false validation-errors] if validations fail (where validation-errors
    is what the validator returned)
    - [false {:base [:insert-error]}] if insertion into the database fails
    - [true created-object] otherwise (created-object will have an :_id and
      :created_at and :updated_at timestamps)"
  [{:keys [database entity] :as model-component} attributes]
  (let [collection
          (:collection entity)
        validate
          (:validator entity)
        empty-callback-fn
          (fn [c r] r)
        before-validation-hook
          (or (:before-validation entity) empty-callback-fn)
        before-validation-on-create-hook
          (or (:before-validation-on-create entity) empty-callback-fn)
        before-save-hook
          (or (:before-save entity) empty-callback-fn)
        before-create-hook
          (or (:before-create entity) empty-callback-fn)
        after-create-hook
          (or (:after-create entity) empty-callback-fn)
        attributes
          (->> attributes
               (before-validation-hook model-component)
               (before-validation-on-create-hook model-component))
        validation-errors
          (and validate (validate model-component attributes))]
    (if (seq validation-errors)
      [false validation-errors]
      ;; If :created_at is specified then that will be used instead of
      ;; the current timestamp (like in Rails). This also works if a
      ;; nil value is specified. (Rails, instead, doesn't honor a
      ;; :created_at set to nil, and overwrites it with the current
      ;; timestamp.)
      ;; Same for :updated_at .
      (let [now
              (time/now)
            record
              (merge (->> attributes
                          (before-save-hook model-component)
                          (before-create-hook model-component))
                     (when-not (contains? attributes :created_at)
                               {:created_at now})
                     (when-not (contains? attributes :updated_at)
                               {:updated_at now}))
            record
              ;; With the default ACKNOWLEDGED value for WriteConcern
              ;; http://docs.mongodb.org/manual/core/write-concern/#default-write-concern
              ;; exceptions are raised for network issues and server errors
              ;; (ex. DuplicateKeyException)
              ;; http://api.mongodb.org/java/current/com/mongodb/WriteConcern.html#ACKNOWLEDGED
              ;; http://api.mongodb.org/java/current/com/mongodb/DuplicateKeyException.html
              (try
                (db/with-mongo (:connection database)
                  ;; insert! returns the inserted object,
                  ;; with the :_id set
                  (db/insert! collection record))
                (catch Exception e
                  (log/info (str "log-message=\"in create\" exception=" e
                                 " collection=" (:collection entity)
                                 " record=" record))
                  nil))]
        (if record
          [true (after-create-hook model-component record)]
          [false {:base [:insert-error]}])))))


(defn find
  "Returns a lazy sequence of records, optionally limited to :limit, matching
  the conditions specified in :where and ordered by :sort (all keys are
  optional).

  If the second parameter contains an :explain? key with a truthy value, then
  a map with information about the query plan is returned instead."
  [{:keys [database entity] :as model-component}
   & [{:keys [where sort limit explain?]}]]
  (db/with-mongo (:connection database)
    (db/fetch (:collection entity)
              :where where
              :sort sort
              :limit limit
              :explain? explain?)))


(defn find-one
  "Returns a map with the first [^1] record matching the conditions specified
  in the `where` parameter, or nil if no record is found.

  (Cannot be used with :sort, use find with :limit 1 instead.)

  [^1]: According to the \"natural order\"
  http://docs.mongodb.org/manual/reference/method/db.collection.findOne/"
  [{:keys [database entity] :as model-component} where]
  (db/with-mongo (:connection database)
    (db/fetch-one (:collection entity) :where where)))

(defn find-by-id
  [model-component id]
  (find-one model-component
            {:_id (if (string? id) (to-object-id id) id)}))

(defn count
  [{:keys [database entity] :as model-component} & [{:keys [where]}]]
  (db/with-mongo (:connection database)
    (db/fetch-count (:collection entity) :where where)))

(defn find-contiguous
  "Returns the document/record that follows (if `order` is 1, the default) or
  precedes (if `order` is -1) the specified `document` when the documents
  matching `conditions` are ordered by `attribute-name`.

  Ex.
  (find-contiguous post-component current-post :posted_at {:author_id <id>} 1)"
  [model-component document attribute-name conditions & [order]]
  (let [comparison-operator
          (if (= order -1) :$lte :$gte)
        base-conditions
          [{attribute-name {comparison-operator (attribute-name document)}}
           {:_id {:$ne (:_id document)}}]
        all-conditions
          (if conditions (conj base-conditions conditions) base-conditions)]
    (first (find model-component
                 {:where {:$and all-conditions}
                  :sort {attribute-name order, :_id order}
                  :limit 1}))))

(defn find-next
  "Returns the document (record) that follows the specified `document` when
  the documents matching `conditions` are ordered by `attribute-name`.

  (Convenience function for `find-contiguous` when the order param is 1.)"
  [model-component document attribute-name & [conditions]]
  (find-contiguous model-component document attribute-name conditions 1))

(defn find-prev
  "Returns the document (record) that precedes the specified `document` when
  the documents matching `conditions` are ordered by `attribute-name`.

  (Convenience function for `find-contiguous` when the order param is -1.)"
  [model-component document attribute-name & [conditions]]
  (find-contiguous model-component document attribute-name conditions -1))


(defn update-all
  "Applies the specified `updates` to all the records that match the
  `conditions`. The `updates` have to specify the update operator to use
  ($set, $unset...).

  Lifecycle callbacks and validations are not triggered.

  The `conditions` MUST be supplied, to prevent the accidental update of all
  the records in a collection. If that's really what's desired, an empty map
  can be passed as the `conditions` parameter.

  Example:
    (update-all product-component
                {:$set {:name \"changed\"}}
                {:name \"test name\"})"
  ;; CongoMongo
  ;; https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo.clj
  ;; calls
  ;; http://api.mongodb.org/java/current/com/mongodb/DBCollection.html#update-com.mongodb.DBObject-com.mongodb.DBObject-boolean-boolean-com.mongodb.WriteConcern-
  ;; which corresponds to this in the mongo shell interface
  ;; http://docs.mongodb.org/manual/core/write-operations-introduction/#update
  [{:keys [database entity] :as model-component} updates conditions]
  (db/with-mongo (:connection database)
    (db/update! (:collection entity) conditions updates :multiple true)))


(defn- compose-callback-fns
  [callback-fns model-component original-record]
  (let [callback-fns
          (if (instance? clojure.lang.Atom callback-fns)
            (deref callback-fns)
            callback-fns)]
    (when callback-fns
      ;; The use of anonymous functions below (`fn`) solves the problem of
      ;; argument order when using partial, as is also explained in
      ;; https://kotka.de/blog/2010/08/Did_you_know_VII.html
      (->> (if (sequential? callback-fns) callback-fns [callback-fns])
           (map (fn [f]
                  (fn [changed-record]
                    (f model-component changed-record original-record))))
           reverse
           (apply comp)))))


(defn- compose-delete-callback-fns
  [callback-fns entity]
  (let [callback-fns
          ;; http://stackoverflow.com/a/11782628
          (if (instance? clojure.lang.Atom callback-fns)
              (deref callback-fns)
              callback-fns)]
    (when callback-fns
      (->> (if (sequential? callback-fns) callback-fns [callback-fns])
           (map (fn [f]
                  (fn [record]
                    (f entity record))))
           reverse
           (apply comp)))))


(defn update
  "If validation succeeds for the record specified by `id` with the changed
  `attributes`, the resulting record is saved (with an automatically
  :updated_at timestamp, unless it's explicitly changed). If `attributes` are
  not really changing anything, nothing is saved.

  Only the $unset \"update operator\" is supported.
  http://docs.mongodb.org/manual/reference/operators/#update

  $unset allows to delete fields. To delete a field, specify :$unset as its
  new value. Example:

    (update user-component
            23
            {:password \"s3cret\" :password_reset_code :$unset})

  Notice that it's possible to delete fields in the same update as others are
  set.

  These callbacks will be called, in the order listed here, if defined in
  the map under the :entity key of the `model-component` parameter:
  - :before-validation
  - :validator
  - :before-save
  - :before-update
  - :on-update-errors
  - :after-update

  All callbacks will be passed the `model-component` and the entire record
  with the corresponding attributes updated. The :validator callback must
  return a collection of errors (empty or nil if no errors), the other
  callbacks must return the entire record, maybe with some attributes
  changed, added, or deleted.

  With MongoDB's default ACKNOWLEDGED value for WriteConcern [^1], the
  :on-update-errors callback is called when there are network issues or
  database server errors (ex. com.mongodb.DuplicateKeyException).

  [^1]: http://docs.mongodb.org/manual/core/write-concern/#default-write-concern

  Returns:
    - [false validation-errors] if validations fail (where validation-errors
      is what the validator returned)
    - [false validation-errors] if no record is found with the specified id
    - [false {:base [:update-error]}] if update fails because of network
      issues or database server errors
    - [true updated-object] otherwise"
  [{:keys [database entity] :as model-component}
   id
   attributes
   & [{:keys [skip-validations] :or {skip-validations false}}]]
  (let [validate
          (:validator entity)
        empty-callback-fn
          (fn [e r] r)
        before-validation-hook
          (or (:before-validation entity) empty-callback-fn)
        old-record
          (find-by-id model-component id)
        unset-map
          (select-keys attributes (for [[k v] attributes
                                        :when (= v :$unset)]
                                    k))
        old-record-without-deleted-fields
          (apply dissoc old-record (keys unset-map))
        attributes-without-deleted-ones
          (apply dissoc attributes (keys unset-map))
        changed-record
          (merge old-record-without-deleted-fields
                 attributes-without-deleted-ones
                 ;; Sets the value of `:_id`, in case it's incorrectly set in
                 ;; the `attributes` param (as nil, an id different than the
                 ;; `id` param, a String instead of an ObjectId...) This
                 ;; makes sure that callbacks, which will receive this
                 ;; changed-record as a parameter, will get the correct value
                 ;; for `:_id`.
                 ;;
                 ;; Any previous value can be safely overridden as MongoDB
                 ;; doesn't allow to modify an _id on an update operation
                 ;; anyway [1][2].
                 ;;
                 ;; [1] http://stackoverflow.com/q/4012855
                 ;; [2] Causes a
                 ;; #<MongoException com.mongodb.MongoException: Mod on _id
                 ;; not allowed>
                 {:_id (:_id old-record)})
        changed-record
          (before-validation-hook model-component changed-record)
        validation-errors
          (when-not skip-validations
            (and validate (validate model-component changed-record)))]
    (if (or (nil? old-record) (seq validation-errors))
      [false validation-errors]
      ;; Updates and timestamps the record ONLY IF there are changes
      (let [before-save-fn (or (:before-save entity) empty-callback-fn)
            prepared-record (before-save-fn model-component changed-record)]
        ;; Note that, because of how prepared-record is obtained, here it has
        ;; the same value for :updated_at as old-record (unless a different
        ;; one was specified in the `attributes` param). This allows to
        ;; determine if the record has to be actually updated using the
        ;; condition below. (If it has, updated_at will be properly set
        ;; later.)
        (if (= prepared-record old-record)

            [true old-record]

            (let [changed-record  ; overrides the outer changed-record
                    (if-let [before-update-fn (:before-update entity)]
                      (before-update-fn model-component prepared-record)
                      prepared-record)
                  updated-at
                    ;; If :updated_at is specified and it's different than
                    ;; the old one, then that will be used instead of the
                    ;; current timestamp, like in Rails. The only problem,
                    ;; like in Rails, is that it's not possible to change
                    ;; the value of an attribute without changing the
                    ;; timestamp (unless it's done in two operations:
                    ;; first the attribute is updated, then a second
                    ;; update is used to replace the new timestamp with
                    ;; the old one).
                    (if (and (contains? prepared-record :updated_at)
                             (not= (:updated_at prepared-record)
                                   (:updated_at old-record)))
                        (:updated_at prepared-record)
                        (time/now))
                  modifications
                    ;; Since MongoDB 2.5, modifiers like $set or $unset
                    ;; can't be empty
                    ;; https://jira.mongodb.org/browse/SERVER-12266
                    (merge ; _id cannot be modified
                           ; http://stackoverflow.com/q/4012855
                           {:$set (assoc (dissoc changed-record :_id)
                                         :updated_at updated-at)}
                           (when (seq unset-map) {:$unset unset-map}))
                  updated-record
                    (try
                      ;; The second argument of MongoDB's update (the 3rd in
                      ;; CongoMongo) is the "updated object or $ operators
                      ;; (e.g., $inc) which manipulate the object"
                      ;; http://www.mongodb.org/display/DOCS/Updating#Updating-update%28%29
                      ;; Notice that an "updated object" represents a whole
                      ;; document, not only the fields that have to be
                      ;; modified. This updated object will completely
                      ;; replace the old object. To modify only some fields,
                      ;; the $ modifiers have to be used.
                      (db/with-mongo (:connection database)
                        (db/update! (:collection entity)
                                    (select-keys old-record [:_id])
                                    modifications))
                      ;; CongoMongo's update! returns a WriteResult from the
                      ;; underlying Java driver
                      ;;   https://github.com/aboekhoff/congomongo#update
                      ;; but the updated record has to be returned, that's
                      ;; why it has to retrieved from the database
                      (find-by-id model-component id)
                      (catch Exception e
                        (log/info (str "log-message=\"in update\" exception="
                                       e " collection=" (:collection entity)
                                       " old-record=" old-record
                                       " modifications=" modifications))
                        (when-let [on-update-errors-fn (:on-update-errors entity)]
                          (on-update-errors-fn model-component changed-record))
                        nil))]
              ;; If there were exceptions on update then :after-update
              ;; callbacks will not be called.
              (if updated-record
                (if-let [composed-after-update-fn
                           (compose-callback-fns (:after-update entity)
                                                 model-component
                                                 old-record)]
                  [true (composed-after-update-fn updated-record)]
                  [true updated-record])
                [false {:base [:update-error]}])))))))


(defn delete
  "If the id parameter is a String, it's automatically converted to an
  ObjectId.

  These callbacks will be called, in the order listed here, if defined in the
  map under the :entity key of the `model-component` parameter:

  - :before-delete
  - :after-delete

  Returns:
    - the number of records deleted"
  [{:keys [database entity] :as model-component} id]
  ;; CongoMongo's destroy! function (which uses the remove method of
  ;; MongoDB's Java driver) is the most obvious way to delete a document, but
  ;; when the "write concern" is set to :unacknowledged (default is
  ;; :acknowledged though), it doesn't allow to know if anything was deleted.
  ;; For other (stronger) "write concerns" it's possible to know the number
  ;; of documents deleted by inspecting the WriteResult Java object that it
  ;; returns.
  ;;
  ;; Because of that, the more general command function is used, as it allows
  ;; to know the number of documents deleted independently of the "write
  ;; concern", and it doesn't require messing with Java.
  ;;
  ;; If nothing was deleted, there's no :lastErrorObject, otherwise it
  ;; contains an :n element with the number of documents deleted.
  ;;
  ;; #TODO
  ;; Review the implementation of this function in light of...
  ;;
  ;; > Changed in version 2.6: A new protocol for write operations integrates
  ;; > write concerns with the write operations, eliminating the need for a
  ;; > separate getLastError. Most write methods now return the status of the
  ;; > write operation, including error information.
  ;; http://docs.mongodb.org/manual/reference/command/getLastError/
  ;;
  ;; CongoMongo provides a fetch-and-modify function that wraps MongoDB's
  ;; findAndModify command, but I don't see the value of it, and I prefer to
  ;; use the generic command function.
  ;; "The findAndModify command modifies and returns a single document."
  ;; http://docs.mongodb.org/manual/reference/command/findAndModify/
  (let [collection (:collection entity)
        id (if (string? id) (to-object-id id) id)
        record (find-by-id model-component id)
        _
          (when-let [before-delete-fn (:before-delete entity)]
            (before-delete-fn model-component record))
        command-result
          (db/with-mongo (:connection database)
            ;; https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo.clj
            ;; http://api.mongodb.org/java/current/com/mongodb/DB.html#command-com.mongodb.DBObject-
            (db/command {:findAndModify (name collection)
                         :query {:_id id}
                         :remove true}))]
    (when-let [composed-after-delete-fn
                 (compose-delete-callback-fns (:after-delete entity)
                                              model-component)]
      (composed-after-delete-fn record))

    (or (get-in command-result [:lastErrorObject :n]) 0)))


(defn delete-all
  "Returns:
     - the number of records deleted"
  [{:keys [database entity] :as model-component} query]
  (let [result
          (db/with-mongo (:connection database)
            (db/destroy! (:collection entity) query))]
    (.getN result)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Pagination

(defn- generate-paging-conditions
  [sort-order start]
  ;; For the moment it assumes that sort-order has only 1 field
  ;; #TODO Add support for any number of sort-order fields.
  (let [[sort-field-1-key sort-field-1-order]
          (first sort-order)  ; sort-order is an array-map, so it's ordered
        sort-field-1-value
          ;; `sort-order` should have the same keys as `start`
          ;; (except maybe :_id)
          (when (seq sort-order) (sort-field-1-key start))
        comparison-operator
          (if (= sort-field-1-order 1) :$gt :$lt)
        ;; assumes last element corresponds to the attribute used to
        ;; disambiguate order, typically _id
        id-comparison-operator
          (if (= (val (last sort-order)) 1) :$gte :$lte)]
    ;; `:sort` (sort-order) may not have been specified
    ;; `start` may not have been specified
    (if (and sort-order
             (not (every? #{:_id} (keys sort-order)))
             (seq start))
      {:$or [{sort-field-1-key {comparison-operator sort-field-1-value}}
             {:$and [{sort-field-1-key sort-field-1-value}
                     {:_id {id-comparison-operator (:_id start)}}]}]}
      (when (seq start)
        {:_id {id-comparison-operator (:_id start)}}))))


(defn- merge-conditions-with-and
  "It also works when `conditions-1` and `conditions-2` are nil."
  [conditions-1 conditions-2]
  (if (and (seq conditions-1) (seq conditions-2))
    {:$and [conditions-1 conditions-2]}
    (if (seq conditions-1)
      conditions-1
      conditions-2)))


(defn page
  "Returns a page of the records specified by :where and ordered by :sort.
  It implements \"range-based pagination\" [^1]. The desired page is
  specified in the `start` parameter as a map with the values of the sort
  fields [^2] that the first record in the page must match.

  [^1] As recommended in MongoDB manual when discussing cursor.skip()
  http://docs.mongodb.org/manual/reference/method/cursor.skip/
  [^2]: Plus the :_id field if necessary to disambiguate

  Ex.
      (page post-component
            ;; The default sort order is `:sort {:_id 1}`. If the :sort map
            ;; has more than 1 element, it should be an array-map, which
            ;; maintains key order.
            ;; When the whole map is nil, it pages through all records with
            ;; the default sort order.
            {:where {:author_id <author_id>} :sort {:posted_at -1}}
            ;; start, when it's nil the 1st page is returned
            {:posted_at
               (clj-time.coerce/from-string \"2015-08-25T14:37:30.947Z\")
             :_id
               (mongologic/to-object-id \"51b5da900364618037ff21e7\")}
            ;; page-size
            100)

  Returns:
      {:items (...)
       :previous-page-start {...}
       :next-page-start {...}}"
  [{:keys [database entity] :as model-component}
   {where :where sort-order :sort}
   start
   page-size]
  (let [full-sort-order
          ;; #TODO What happens when a explicit {:_id 1} sort order is
          ;; specified? And if it's {:_id -1}?
          ;;
          ;; sort-order should be an array-map, and this adds `:_id 1` to the
          ;; end of this array-map . It doesn't use
          ;;   (into sort-order {:_id 1})
          ;; because possibly that counts as a "modified" array map, which
          ;; may not maintain the sort order.
          ;; http://clojure.org/data_structures#Data Structures-ArrayMaps
          (apply array-map (flatten (conj (vec sort-order) [:_id 1])))

        paging-conditions
          (generate-paging-conditions full-sort-order start)

        records-batch
          ;; Notice that...
          ;; - `where` may be empty, when paging through all the records of
          ;;   the collection
          ;; - `paging-conditions` may be empty, when the first page has to
          ;;    be served
          ;; ...but merge-conditions-with-and will do the right thing in any
          ;; case
          (find model-component
                {:where (merge-conditions-with-and where paging-conditions)
                 :sort full-sort-order
                 :limit (inc page-size)})

        next-page-start
          (when (> (clojure.core/count records-batch) page-size)
            (select-keys (last records-batch) (keys full-sort-order)))
        page-records
          (if next-page-start (butlast records-batch) records-batch)

        ;; (array-map :updated_at -1 :_id 1)
        ;; =>
        ;; (array-map :updated_at 1 :_id -1)
        ;; The index will support the reverse order, see
        ;; http://docs.mongodb.org/manual/core/index-compound/#sort-order
        reverse-sort-order
          (apply array-map
                 (flatten (map (fn [[field order]] [field (* -1 order)])
                               full-sort-order)))

        prev-page-paging-conditions
          (generate-paging-conditions reverse-sort-order start)
        prev-page-records-batch
          (when (seq start)
            (find model-component
                  {:where
                     (merge-conditions-with-and where
                                                prev-page-paging-conditions)
                   :sort
                     reverse-sort-order
                   :limit
                     (inc page-size)}))
        prev-page-start
          (when (and (seq start)
                     (> (clojure.core/count prev-page-records-batch) 1))
            (select-keys (last prev-page-records-batch)
                         (keys full-sort-order)))]

    {:items page-records
     :previous-page-start prev-page-start
     :next-page-start next-page-start}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Validations

(defn unique?
  "Checks that there are no other records in the collection corresponding to
  model-component with the same values for the combination of fields
  specified in unique-key-fields. The uniqueness constraint can be scoped to
  records matching scope-conditions (the default scope is the whole
  collection). Ex.

      (unique? book-component
               book-record
               [:title :author_id]
               {:status \"active\"})

  If the record's attributes contain an :_id, it's assumed that these
  attributes correspond to an existing record and the uniqueness check will
  not take into account any record with that same :_id (so it will not take
  itself into account)."
  [model-component attributes unique-key-fields & [scope-conditions]]
  (let [_id
          (:_id attributes)
        unique-fields-conditions
          ;; if attributes is {:author_id 123}
          ;; unique-key-fields is [:title :author_id]
          ;; =>
          ;; {:title nil :author_id 123}
          ;; http://stackoverflow.com/a/5543309
          ;;
          ;; Notice that in MongoDB a query like { title: null } matches
          ;; documents that either contain the 'title' field whose value is
          ;; null or that do not contain the title field.
          ;; http://docs.mongodb.org/manual/faq/developers/#faq-developers-query-for-nulls
          (merge (zipmap unique-key-fields (repeat nil))
                 (select-keys attributes unique-key-fields))]
    (not (find-one
          model-component
          (merge-conditions-with-and
           (merge unique-fields-conditions
                  (when _id {:_id {:$ne (to-object-id _id)}}))
           scope-conditions)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Utilities

(defn add-to-callback-queue
  "#IMPORTANT
  Callback queues currently only supported in :after-update and :after-delete

  Example:

       (swap! (get-in component [:entity :after-update])  ; must be an atom
              add-to-callback-queue
              new-callback-fn)"
  [callback-queue callback-fn]
  (cond
    (sequential? callback-queue) (conj callback-queue callback-fn)
    (nil? callback-queue) [callback-fn]
    :else (conj [callback-queue] callback-fn)))


(defn beginning-of-day
  "Returns a map, to be used in the context of CongoMongo's interface to
  MongoDB's aggregation framework [^1][^2], to get a Date value with the same
  day as the one resulting from adding utc-offset-in-milliseconds to the
  specified date-field (expected as a keyword, like in :field-name), but with
  a time of 0:00 .

  Ex.

      (beginning-of-day :created_at 7200000)
      =>
      {:$subtract
         [{:$add [\"$created_at\" 7200000]}
          {:$add [{:$millisecond [{:$add [\"$created_at\" 7200000]}]}
                  {:$multiply [{:$second [{:$add [\"$created_at\" 7200000]}]}
                               1000]}
                  {:$multiply [{:$minute [{:$add [\"$created_at\" 7200000]}]}
                               60
                               1000]}
                  {:$multiply [{:$hour [{:$add [\"$created_at\" 7200000]}]}
                               60
                               60
                               1000]}]}]}

  [^1]: https://github.com/aboekhoff/congomongo#aggregation-requires-mongodb-22-or-later
  [^2]: http://docs.mongodb.org/manual/core/aggregation-pipeline/"
  [date-field utc-offset-in-milliseconds]
  ;; Ideally, a time-zone would be specified instead of
  ;; utc-offset-in-milliseconds. Then a datetime stored in the database as
  ;; UTC could be converted to local time with that time zone, taking into
  ;; account daylight saving time. Unfortunately, as of version 2.4 of
  ;; MongoDB, it seems there's no way to convert a date to a given time zone
  ;; in the context of the aggregation framework.
  ;;
  ;; http://www.kamsky.org/1/post/2013/03/stupid-date-tricks-with-aggregation-framework.html
  ;;
  ;; http://docs.mongodb.org/manual/reference/operator/aggregation/
  (let [field (str "$" (name date-field))
        offset-date-time {:$add [field utc-offset-in-milliseconds]}
        ;; The wrapping of $add in an array is a temporary work around to a
        ;; MongoDB bug that should be fixed in version 2.6
        ;; https://jira.mongodb.org/browse/SERVER-6310?focusedCommentId=431343&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-431343
        ;; #TODO See if it's possible to get rid of this wrapping now that
        ;; MongoDB is at version 3
        offset-date-time* [offset-date-time]]
    {:$subtract [offset-date-time
                 {:$add [{:$millisecond offset-date-time*}
                         {:$multiply [{:$second offset-date-time*}
                                      1000]}
                         {:$multiply [{:$minute offset-date-time*}
                                      60
                                      1000]}
                         {:$multiply [{:$hour offset-date-time*}
                                      60
                                      60
                                      1000]}]}]}))


(defn to-object-id
  "Returns the MongoDB ObjectId corresponding to id, which may be the
  hexadecimal string value of the ObjectId, or the ObjectId itself.
  It may raise an exception if the id is not a valid ObjectId (ex. if it's
  the empty string, or nil)."
  [id]
  ;; The object-id function raises an exception when passed an ObjectId as a
  ;; parameter. The code below works around this by first converting to a
  ;; string. This allows the function to work for both String and ObjectId
  ;; input params.
  ;; https://github.com/aboekhoff/congomongo/issues/77
  ;; http://docs.mongodb.org/manual/reference/object-id/
  (db/object-id (str id)))
