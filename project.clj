(defproject org.thnetos/cd-client "0.3.5-SNAPSHOT"
  :url "https://github.com/dakrone/clojuredocs-client"
  :description "A client for the clojuredocs API"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.reader "1.3.3"]
                 ;; clj-http versions:
                 ;; 1.0.1 released 2014-Oct-28
                 ;; 1.1.2 is last 1.x release from 2015-May-06
                 ;; 3.10.3 released 2020-Sep-18 and latest as of 2020-Oct-04
                 [clj-http "1.0.1"]
                 ;; cheshire versions:
                 ;; 5.3.1 released 2014-Jan-10
                 ;; 5.10.0 released 2020-Feb-04 and latest as of 2020-Oct-04
                 [cheshire "5.3.1"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.trace "0.7.10"]]}})
