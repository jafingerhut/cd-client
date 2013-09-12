(defproject org.thnetos/cd-client "0.3.5-SNAPSHOT"
  :url "https://github.com/dakrone/clojuredocs-client"
  :description "A client for the clojuredocs API"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.reader "0.7.6"]
                 [clj-http "0.7.6"]
                 [cheshire "5.2.0"]]
  :profiles
  {:dev
   {:dependencies [[org.clojure/tools.trace "0.7.6"]
                   ;; [clj-ns-browser "1.3.2-SNAPSHOT"]
                   ]}})
