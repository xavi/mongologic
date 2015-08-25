(ns mongologic.core-test
  (:require [clojure.test :refer :all]
            [mongologic.core :as mongologic]
            [somnium.congomongo :as congomongo]))


(def conn (congomongo/make-connection "mongodb://localhost/mongologic_test"))

(def ^:dynamic *mock-calls*)


; (defn init-database []
;   (congomongo/with-mongo conn
;     (congomongo/drop-coll! :books)
;     (congomongo/create-collection! :books)
;     (congomongo/add-index! :books [:isbn] :unique true)))

(defn purge-collection
  [f]
  (congomongo/with-mongo conn (congomongo/drop-coll! :books))
  (f)
  (congomongo/with-mongo conn (congomongo/drop-coll! :books)))

(use-fixtures :each purge-collection)

(deftest test-update
  (binding [*mock-calls* (atom [])]
    (let [mock-callback
            (fn [callback-key]
              (fn [& [component record :as args]]
                (swap! *mock-calls* conj [callback-key args])
                ;; Converts callback-key keyword to string as it will be
                ;; converted to a string anyway when saving in MongoDB.
                ;; CongoMongo only supports round trip keywords when
                ;; these are map keys
                ;; https://github.com/aboekhoff/congomongo/blob/master/test/somnium/test/congomongo.clj#L680
                ;; https://github.com/aboekhoff/congomongo/blob/master/src/somnium/congomongo/coerce.clj
                ;;
                ;; Updates :history attribute only on update (not on create)
                (if (:_id record)
                  (update record
                          :history
                          ;; Conjoining to nil works but the result is a
                          ;; list. The conj function prepends to lists and
                          ;; appends to vectors. In this case a vector is
                          ;; desired, and `(or % [])` is used to get it
                          ;; http://stackoverflow.com/q/5734435
                          #(conj (or % []) (name callback-key)))
                  record)))
          component
            {:database
               {:connection conn}
             :entity
               {:collection
                  :books
                :before-validation
                  (mock-callback :before-validation)
                :validator
                  (fn [& args]
                    (swap! *mock-calls* conj [:validator args])
                    nil)
                :before-save
                  (mock-callback :before-save)
                :before-update
                  (mock-callback :before-update)
                :on-update-errors  ; not called on succesful updates
                  (fn [& args]
                    (swap! *mock-calls* conj [:on-update-errors args]))
                :after-update
                  (mock-callback :after-update)}}
          [book1-success book1]
            (mongologic/create component {:isbn "978-3-16-148410-1"})
          [book1-updated-success book1-updated]
            (do
              (reset! *mock-calls* [])  ; clears calls recorded on creation
              (mongologic/update component
                                 (:_id book1)
                                 {:isbn "978-3-16-148410-2"}))]
      (testing "Callbacks are called in the right order"
        (doseq [[expected-callback [callback args]]
                  (map #(vector %1 %2)
                       [:before-validation :validator :before-save
                        :before-update :after-update]
                       @*mock-calls*)]
          (is (= callback expected-callback))
          (is (= (first args) component))))
      (testing "Record changes add up and pass through callbacks"
               ;; except for :validator and :on-update-errors, which can't
               ;; update the record (:after-update can't update the stored
               ;; record either, but it can update the returned record)
        (is (= (:history book1-updated)
               ["before-validation"
                "before-save"
                "before-update"
                "after-update"])))
      ;; https://github.com/rails/rails/blob/master/activerecord/test/cases/persistence_test.rb
      (testing "Record is updated"
        (is book1-updated-success)
        (doseq [expected-book1
                  [{:_id (:_id book1) :isbn "978-3-16-148410-2"}]
                resulting-record
                  [book1-updated
                   (mongologic/find-by-id component (:_id book1))]]
          (is (= (select-keys resulting-record [:_id :isbn])
                 expected-book1)))))))

;; https://github.com/rails/rails/blob/master/activerecord/test/cases/validations/uniqueness_validation_test.rb
(deftest test-unique-validation
  (let [component
          {:database {:connection conn}
           :entity {:collection :books}}
        book1
          {:isbn "978-3-16-148410-1"}
        book2
          {:isbn "978-3-16-148410-2"}]
    (is (first (mongologic/create component book1))
        "Should succeed")
    (is (false? (mongologic/unique? component book1 [:isbn]))
        "Should not be unique")
    (is (mongologic/unique? component book2 [:isbn])
        "Should be unique")))

(deftest test-create-validator-callback
  (let [validator
          (fn [component book]
            (when-not (mongologic/unique? component book [:isbn])
              [:taken]))
        component
          {:database {:connection conn}
           :entity {:collection :books
                    :validator validator}}
        book1
          {:isbn "978-3-16-148410-1"}]
    (is (first (mongologic/create component book1))
        "Should succeed")
    (is (= [false [:taken]] (mongologic/create component book1))
        "Should return validation errors")))

(deftest test-create-insertion-error
  (congomongo/with-mongo conn
    (congomongo/create-collection! :books)
    (congomongo/add-index! :books [:isbn] :unique true))
  (let [component
          {:database {:connection conn}
           :entity {:collection :books}}
        book1
          {:isbn "978-3-16-148410-1"}]
    (is (first (mongologic/create component book1))
        "Should succeed")
    (is (= [false {:base :insert-error}] 
           (mongologic/create component book1))
        "Should return :insert-error")))


;; Based on Rathore's "Clojure in Action",
;; Chapter 10: Test-driven development and more
;; https://aphyr.com/posts/306-clojure-from-the-ground-up-state
;; Rails callbacks testing
;; https://github.com/rails/rails/blob/master/activerecord/test/cases/callbacks_test.rb

(deftest test-update-error
  ;; #TODO DRY, also used in test-create-insertion-error
  (congomongo/with-mongo conn
    (congomongo/create-collection! :books)
    (congomongo/add-index! :books [:isbn] :unique true))
  (binding [*mock-calls* (atom [])]
    (let [component
            {:database
               {:connection conn}
             :entity
               {:collection :books
                :on-update-errors
                  (fn [& args]
                    (swap! *mock-calls* conj args))}}
          [book1-success book1]
            (mongologic/create component {:isbn "978-3-16-148410-1"})
          [book2-success book2]
            (mongologic/create component {:isbn "978-3-16-148410-2"})]
      (is book1-success "Should create book1 successfully")
      (is book2-success "Should create book2 successfully")
      (testing "An :update-error is returned on database-level update errors"
        (is (= [false {:base :update-error}]
               (mongologic/update component
                                  (:_id book1)
                                  {:isbn (:isbn book2)}))
            (str "Should return :update-error when trying to violate a "
                 "database-level unique constraint")))
      (testing "The :on-update-errors callback is called"
        (is (= 1 (count @*mock-calls*))
            "Should have called the callback once")
        (let [[arg1 arg2] (first @*mock-calls*)]
          (is (= arg1 component)
              (str "Should have called the callback with the component as the "
                   "first argument"))
          (is (= (select-keys arg2 [:_id :isbn])
                 {:_id (:_id book1) :isbn (:isbn book2)})
              (str "Should have called the callback with the changed (but "
                   "unsaved) record as the second argument")))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; "If you want a report of passing tests; you can run with the TAP output 
;; adapter in clojure.test.tap. "
;; https://groups.google.com/d/msg/clojure/cwjf2dSg9mQ/_2_Wj90aVlMJ
;; http://clojure.github.io/clojure/clojure.test-api.html#clojure.test.tap
;;
;; (use 'clojure.test)
;; (use 'clojure.test.tap)
;; (use 'mongologic.core-test)
;; (with-tap-output (run-tests 'mongologic.core-test))
;;
;; BUT this method is probably better (see http://stackoverflow.com/a/24337705)
;;
;; (require '[clojure.test :refer [run-tests]])
;; (require 'mongologic.core-test)   ;; I expected this to not be necessary, but it is
;; (run-tests 'mongologic.core-test)
;;
;; #TOLEARN
;; When tests are changed, they have to be reloaded for the REPL to pick up
;; the changes. They can be reloaded with
;; (require 'mongologic.core-test :reload)
;; or
;; (require 'mongologic.core-test :reload-all)
;;
;; Difference being... 
; :reload forces loading of all the identified libs even if they are
;   already loaded
; :reload-all implies :reload and also forces loading of all libs that the
;   identified libs directly or indirectly load via require or use

;; #TOLEARN
;; Run just 1 test
;;  (clojure.test/test-vars [#'the-ns/the-test])
;; http://stackoverflow.com/questions/24970853/run-one-clojure-test-not-all-tests-in-a-namespace-with-fixtures-from-the-rep
;; <= Notice that if no failures nothing is printed

