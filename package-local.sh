lein clean && lein test && lein uberjar && rm -rf ~/.m2/repository/org.crossref && lein localrepo install target/uberjar/org.crossref.event-data-agent-framework-0.1.0-standalone.jar org.crossref.event-data-agent-framework "0.1.3"

