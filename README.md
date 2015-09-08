# Mongologic

Mongologic provides several tools to make the development of MongoDB-backed
applications easier and faster:

- Callbacks in the lifecycle of records (à la Rails' Active Record)
- Uniqueness validation
- Range-based pagination
- History

Mongologic came out of building [Omnimemory](https://omnimemory.com/),
a flashcards app.


## Installation

Add this to your project's dependencies:

    [mongologic "0.5.0"]


## Documentation

- [API Docs](http://xavi.github.io/mongologic)


## Usage

Most of Mongologic functions expect a "model component" as their first
parameter. At the minimum, this component has to specify a MongoDB database
connection (as returned by the `make-connection` function of
[CongoMongo](https://github.com/aboekhoff/congomongo), the library on which
Mongologic is based) and a collection name. It will often specify some
callbacks too, like `:before-validation`, `:validator` and `:before-delete`
in the example below

```clojure
(ns hello.core
  (:require [mongologic.core :as mongologic]
            [mongologic.history :as mongologic.history]
            [somnium.congomongo :as congomongo]))

(def conn
  (congomongo/make-connection "mongodb://localhost/mongologic_test"))

(def book-component
  {:database {:connection conn}
   :entity {:collection
              "books"
            :history-collection
              "books.history"
            :before-validation
              (fn [component attrs]
                (update attrs :isbn clojure.string/trim))
            :validator
              (fn [component attrs]
                (if (mongologic/unique? component attrs [:isbn])
                  nil
                  {:isbn [:duplicate-book]}))
            :before-delete
              (fn [component attrs]
                (mongologic.history/save-delete component
                                                (:_id attrs)))}})

(mongologic/create book-component {:isbn "978-3-16-148410-1 "})
;; =>
;; [true {:_id #object[org.bson.types.ObjectId 0x1b440bc2 "55ec79266588fe5513f9a2a3"],
;;        :updated_at #object[org.joda.time.DateTime 0x2c037732 "2015-09-06T17:34:30.489Z"],
;;        :created_at #object[org.joda.time.DateTime 0x4e87af97 "2015-09-06T17:34:30.489Z"],
;;        :isbn "978-3-16-148410-1"}]

(mongologic/create book-component {:isbn "978-3-16-148410-1"})
;; =>
;; [false {:isbn [:duplicate-book]}]

(def book (mongologic/find-one book-component {:isbn "978-3-16-148410-1"}))
(mongologic/delete book-component (:_id book))
;; =>
;; 1

(mongologic.history/find-all-by-record-id book-component (:_id book))
;; =>
;; ({:deleted_at #object[org.joda.time.DateTime 0x79eab046 "2015-09-06T17:36:18.172Z"],
;;   :updated_at #object[org.joda.time.DateTime 0x37e375cf "2015-09-06T17:36:18.172Z"],
;;   :created_at #object[org.joda.time.DateTime 0x2d183aa3 "2015-09-06T17:36:18.172Z"],
;;   :_id {:updated_at #object[org.joda.time.DateTime 0x5aab334a "2015-09-06T17:36:18.172Z"],
;;         :_id #object[org.bson.types.ObjectId 0x766fc0d9 "55ec79266588fe5513f9a2a3"]}}
;;  {:isbn "978-3-16-148410-1",
;;   :created_at #object[org.joda.time.DateTime 0x5a8e8ed6 "2015-09-06T17:34:30.489Z"],
;;   :updated_at #object[org.joda.time.DateTime 0x36af7a06 "2015-09-06T17:34:30.489Z"],
;;   :_id {:updated_at #object[org.joda.time.DateTime 0x351d2efa "2015-09-06T17:34:30.489Z"],
;;         :_id #object[org.bson.types.ObjectId 0x3d7c5988 "55ec79266588fe5513f9a2a3"]}})
```

(Each model component in an app could be a component in
[Stuart Sierra's Component framework](https://github.com/stuartsierra/component),
all of them using a common database component to share the connection.)


## License

Copyright © 2015 Xavi Caballé

Licensed under the [Eclipse Public License](LICENSE).
