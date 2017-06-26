(ns org.crossref.event-data-agent-framework.core
  (:require [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [clojure.core.async :refer [thread]]
            [overtone.at-at :as at-at]
            [clj-http.client :as client]
            [config.core :refer [env]]
            [clj-time.core :as clj-time]
            [clj-time.format :as clj-time-format]
            [robert.bruce :refer [try-try-again]]
            [event-data-common.artifact :as artifact]
            [event-data-common.backoff :as backoff]
            [event-data-common.status :as status]
            [clojure.core.async :refer [go-loop thread buffer chan <!! >!! >! <!]])
  (:import [java.net URL]
           [java.io FileOutputStream File]
           [java.nio.channels ReadableByteChannel Channels]
           [org.apache.kafka.clients.producer KafkaProducer Producer ProducerRecord]
           [org.apache.kafka.clients.consumer KafkaConsumer Consumer ConsumerRecords]
           [java.util UUID])
  (:gen-class))

(def kafka-producer
  (delay
    (let [properties (java.util.Properties.)]
      (.put properties "bootstrap.servers" (:global-kafka-bootstrap-servers env))
      (.put properties "acks", "all")
      (.put properties "retries", (int 5))
      (.put properties "key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
      (.put properties "value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
      (KafkaProducer. properties))))

(defn check-definition
  "Checks an Agent definition, returns a list of errors or nil for OK."
  [input]
  (not-empty
    (concat
      (when-not (:agent-name input) ["Missing agent-name"])
      (when-not (:schedule input) ["Missing schedule"])
      (when-not (:jwt input) ["Missing JWT"])
      (when-not (:runners input) ["Missing runners"]))))

(defn check-definition!
  "Check a definition, log and exit if there are errors."
  [definition]
  (when-let [definition-errors (check-definition definition)]
    (doseq [er definition-errors]
      (log/error "Agent error: " er))
    (log/fatal "Agent definition incorrect, exiting.")
    (System/exit 1)))

(def schedule-pool (at-at/mk-pool))

(defn start-heartbeat
  "Schedule a named heartbeat with the Status server to trigger once a minute."
  [service component fragment]
  (at-at/every 60000 #(status/send! service component fragment) schedule-pool))

(defn fetch-artifacts
  "Download seq of artifact names into a map of {artifact-name [version-url text-content]}."
  [agent-definition artifact-names]
  (into {} (map (fn [artifact-name]
                  [artifact-name [(artifact/fetch-latest-version-link artifact-name)
                                  (artifact/fetch-latest-artifact-string artifact-name)]])
                artifact-names)))

(def retry-delay (atom 1000))
(def retries 10)

(def date-format
  (clj-time-format/formatters :basic-date))

(defn decorate-evidence-record
  "Associate an evidence and date stamp at the same time.
   ID has YYYYMMDD prefix to make downstream analysis workflows a bit easier."
  [evidence-record agent-definition]
  (let [now (clj-time/now)
        id (str
             (clj-time-format/unparse date-format now)
             "-" (:source-id evidence-record) "-"
             (UUID/randomUUID))
        now-str (str now)]
    (assoc evidence-record
      :jwt (:jwt agent-definition)
      :id id
      :timestamp now-str)))

(defn callback
  [agent-definition evidence-record]
  (let [topic (:percolator-input-evidence-record-topic env)
        decorated (decorate-evidence-record evidence-record agent-definition)
        id (:id decorated)]
    (status/send! (:agent-name agent-definition) "input-bundle" "occurred")
    (log/info "Send" id "to" topic)
    (.send @kafka-producer (ProducerRecord. topic
                                            id
                                            (json/write-str decorated)))))

(defn run
  [agent-definition]

  (check-definition! agent-definition)
  (start-heartbeat (:agent-name agent-definition) "heartbeat" "tick")

  (log/info "Starting agent...")
  (doseq [schedule-item (:schedule agent-definition)]
    (log/info "Scheduling " (:name schedule-item) "every" (:seconds schedule-item) "seconds")
    (at-at/every (* 1000 (:seconds schedule-item))
                 (fn []
                   (try
                    (log/info "Execute schedule" (:name schedule-item))
                    (status/send! (:agent-name agent-definition) "schedule" (:name schedule-item))
                    (let [artifacts (fetch-artifacts agent-definition (:required-artifacts schedule-item))]
                      ((:fun schedule-item) artifacts (partial callback agent-definition)))
                    (catch Exception e (log/error "Error in schedule" e))))
                 schedule-pool
                 ; Default to fixed-delay true (i.e. wait n seconds after the task completes)
                 ; but can be configured false (i.e. run schedule every n seconds)
                 :fixed-delay (:fixed-delay schedule-item true)))
  
  ; NB doesn't yet fetch artifacts.
  (doseq [runner (:runners agent-definition)]
    (log/info "Starting Agent runner" (:name runner))
    (let [num-threads (get runner :threads 1)]
      (dotimes [t num-threads]
        (log/info "Starting thread" t "for" (:name runner))
        (thread
          ((:fun runner) {} (partial callback agent-definition)))))))
