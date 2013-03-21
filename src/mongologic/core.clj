(ns mongologic.core
  (:require [somnium.congomongo :as db]
            [clj-time.core :as time]
            [noir.validation :as vali]))

; In the namespace of each of the models define the entity that will be
; passed as the first parameter to any call to a mongologic function. An
; entity is a map like this...
;
; (def user-entity
;   {:collection :users
;    :validator valid?
;    :before-save prepare-for-save})
;
; The values for :validator and :before-save must be functions accepting a
; map (representing a record) as a parameter.
; The :validator function must return a truthy or falsey value.
; The :before-save function must return the (possibly updated) record map.
; If the entity doesn't require validation or a before-save hook, leave out
; the corresponding element.
;
; References:
; http://elhumidor.blogspot.com.es/2012/11/why-not-to-use-my-library-clj-record.html
; http://guides.rubyonrails.org/active_record_validations_callbacks.html
; http://sqlkorma.com

(defn create
  "Returns:
    - nil if validations or insertion into the database fails
    - the created object, with timestamps, if validations are ok and it's
      successfully saved

  See:
    http://api.rubyonrails.org/classes/ActiveRecord/Persistence/ClassMethods.html#method-i-create
    http://api.rubyonrails.org/classes/ActiveRecord/Validations/ClassMethods.html#method-i-create-21"
  [entity attributes]
  (let [collection (:collection entity)
        validate (:validator entity)
        before-save-hook (or (:before-save entity) identity)]
    (when (or (nil? validate) (validate attributes))
            ; If "write concern" is set to :safe (see models.clj),
            ; "Exceptions are raised for network issues, and server errors;
            ; waits on a server for the write operation"
            ; http://api.mongodb.org/java/2.7.3/com/mongodb/WriteConcern.html#SAFE
            ; "server errors" may be, for example, duplicate key errors
            ;
            ; DuplicateKey E11000 duplicate key error index: heroku_app2289247.users.$email_1  dup key: { : null }  com.mongodb.CommandResult.getException (CommandResult.java:85)
            ; https://github.com/aboekhoff/congomongo/pull/62#issuecomment-5249364
            (try
              ; If :created_at is specified then that will be used instead of
              ; the current timestamp (like in Rails). This also works if a
              ; nil value is specified. (Rails, instead, doesn't honor a
              ; :created_at set to nil, and overwrites it with the current
              ; timestamp.)
              ; Same for :updated_at .
              (let [now (time/now)]
                ; insert! returns the inserted object, with the :_id set
                (db/insert! collection 
                            (merge (before-save-hook attributes)
                                   (when-not (contains? attributes :created_at)
                                             {:created_at now})
                                   (when-not (contains? attributes :updated_at)
                                             {:updated_at now}))))
              (catch Exception e
                (println e)
                (vali/set-error :base :insert-error)
                nil)))))

(defn find-one [entity where]
  (db/fetch-one (:collection entity) :where where))

(defn find-by-id [entity id]
  ; The object-id function raises an exception when passed an ObjectId as a
  ; parameter. The code below works around this by first converting to a
  ; string. This allows the function to work for both String and ObjectId
  ; input params.
  (find-one entity {:_id (db/object-id (str id))}))

