(defproject org.thnetos/cd-client "0.3.5-SNAPSHOT"
  :url "https://github.com/dakrone/clojuredocs-client"
  :description "A client for the clojuredocs API"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.reader "0.8.12"]
                 [clj-http "1.0.1"]
                 [cheshire "5.3.1"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.trace "0.7.6"]
                                  ;; [clj-ns-browser "1.3.2-SNAPSHOT"]
                                  ]}})
