(defproject mongologic "0.5.6"
  :description "Toolkit to develop MongoDB apps with Clojure"
  :url "https://github.com/xavi/mongologic"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [congomongo "0.4.8"]
                 [clj-time "0.11.0"]
                 [com.taoensso/timbre "4.3.1"]]
  :plugins [[lein-codox "0.9.4"]]
  :codox {
    :source-uri
      "https://github.com/xavi/mongologic/blob/{version}/{filepath}#L{line}"})
