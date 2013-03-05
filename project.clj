(defproject org.thnetos/cd-client "0.3.5-SNAPSHOT"
  :url "https://github.com/dakrone/clojuredocs-client"
  :description "A client for the clojuredocs API"
  ;; Note: The code worked fine with clojure version 1.3.0 as of Feb
  ;; 23 2013.  The dependency on 1.5.0 is simply there to perform
  ;; a small amount of testing on a more recent Clojure version.
  :dependencies [[org.clojure/clojure "1.5.0"]
                 [org.clojure/tools.reader "0.7.2"]
                 [clj-http "0.3.5"]
                 [cheshire "3.1.0"]])
