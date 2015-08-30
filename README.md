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

- [API Docs](http://xavi.github.com/mongologic)


## Usage

Most of Mongologic functions expect a "model component" as their first
parameter. At the minimum, this component has to specify a MongoDB database
connection (as returned by the `make-connection` function of
[CongoMongo](https://github.com/aboekhoff/congomongo), the library on which
Mongologic is based) and a collection name. It will often specify some
callbacks too, like :before-save in the example below

    (ns hello.core
      (:require [mongologic.core :as mongologic]
                [somnium.congomongo :as congomongo]))

    (def conn
      (congomongo/make-connection "mongodb://localhost/mongologic_test"))
    (def book-component
      {:database {:connection conn}
       :entity {:collection "books"
                :before-save (fn [component attrs]
                               (update attrs :isbn clojure.string/trim))}})

    (mongologic/create book-component {:isbn "978-3-16-148410-1 "})

(Each model component in an app can be a component in
[Stuart Sierra's Component framework](https://github.com/stuartsierra/component),
all of them using a common database component to share the connection.)


## License

Copyright © 2015 Xavi Caballé

Licensed under the [Eclipse Public License](LICENSE).
