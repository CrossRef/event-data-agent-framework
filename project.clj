(defproject event-data-agent-framework "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [yogthos/config "0.8"]
                 [clj-time "0.12.0"]
                 [org.clojure/data.json "0.2.6"]
                 [crossref-util "0.1.10"]
                 [http-kit "2.1.18"]
                 [http-kit.fake "0.2.1"]
                 [overtone/at-at "1.2.0"]
                 [robert/bruce "0.8.0"]
                 [org.clojure/tools.logging "0.3.1"]]
  :main ^:skip-aot event-data-agent-framework.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
