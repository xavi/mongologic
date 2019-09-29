(defproject mongologic "0.6.0"
  :description "Toolkit to develop MongoDB apps with Clojure"
  :url "https://github.com/xavi/mongologic"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [congomongo "1.1.0"]
                 [clj-time "0.15.2"]
                 [com.taoensso/timbre "4.10.0"]]
  :plugins [[lein-codox "0.10.7"]]
  :codox {
    :source-uri
      "https://github.com/xavi/mongologic/blob/{version}/{filepath}#L{line}"})
