(defproject org.crossref.event-data-agent-framework "0.1.3"
  :description "Framework for writing Crossref Event Data agents."
  :url "http://eventdata.crossref.org"
  :license {:name "The MIT License (MIT)"
            :url "https://opensource.org/licenses/MIT"}
  :plugins [[lein-localrepo "0.5.3"]]
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [yogthos/config "0.8"]
                 [clj-time "0.12.0"]
                 [org.clojure/data.json "0.2.6"]
                 [crossref-util "0.1.10"]
                 [http-kit "2.1.18"]
                 [http-kit.fake "0.2.1"]
                 [overtone/at-at "1.2.0"]
                 [robert/bruce "0.8.0"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.apache.logging.log4j/log4j-core "2.6.2"]
                 [org.slf4j/slf4j-simple "1.7.21"]]
  :main ^:skip-aot org.crossref.event-data-agent-framework.core
  :target-path "target/%s"
  :aot [org.crossref.event-data-agent-framework.core]
  :profiles {:uberjar {:aot :all}
             :test {:resource-paths ["config/test"]}})
