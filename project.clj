(defproject mongologic "0.5.0"
  :description "Toolkit to develop MongoDB apps with Clojure"
  :url "https://github.com/xavi/mongologic"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [congomongo "0.4.6"]
                 [clj-time "0.10.0"]
                 [com.taoensso/timbre "4.1.0"]]
  :plugins [[codox "0.8.13"]]
  :codox {:src-dir-uri "https://github.com/xavi/mongologic/blob/0.5.0/"
          :src-linenum-anchor-prefix "L"})
