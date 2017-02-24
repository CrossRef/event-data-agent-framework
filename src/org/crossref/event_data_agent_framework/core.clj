(ns org.crossref.event-data-agent-framework.core
  (:require [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [clojure.core.async :refer [thread]]
            [overtone.at-at :as at-at]
            [clj-http.client :as client]
            [config.core :refer [env]]
            [robert.bruce :refer [try-try-again]]
            [event-data-common.artifact :as artifact]
            [event-data-common.backoff :as backoff]
            [event-data-common.status :as status]
            [clojure.core.async :refer [go-loop thread buffer chan <!! >!! >! <!]])
  (:import [java.net URL]
           [java.io FileOutputStream File]
           [java.nio.channels ReadableByteChannel Channels])
  (:gen-class))

(defn check-definition
  "Checks an Agent definition, returns a list of errors or nil for OK."
  [input]
  (not-empty
    (concat
      (when-not (:agent-name input) ["Missing agent-name"])
      (when-not (:schedule input) ["Missing schedule"])
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
  (at-at/every 60000 #(status/send! service component fragment 1) schedule-pool))

(defn fetch-artifacts
  "Download seq of artifact names into a map of {artifact-name [version-url text-content]}."
  [agent-definition artifact-names]
  (into {} (map (fn [artifact-name]
                  [artifact-name [(artifact/fetch-latest-version-link artifact-name)
                                  (artifact/fetch-latest-artifact-string artifact-name)]])
                artifact-names)))

(def input-bundle-chan (chan))

(def retry-delay (atom 1000))
(def retries 10)

(defn start-input-bundle-processing
  [agent-definition]
  (let [url (str (:percolator-url-base env) "/input")
        headers  {"Content-Type" "application/json"
                  "Authorization" (str "Bearer " (:jwt-token env))}]
  (log/info "Starting input bundle sending loop. URL:" url)
  (go-loop [input-bundle (<! input-bundle-chan)]
    (status/send! (:agent-name agent-definition) "input-bundle" "occurred" 1)
    (backoff/try-backoff
      ; Exception thrown if not 200 or 201, also if some other exception is thrown during the client posting.
      #(let [response (client/post url {:headers headers :body (json/write-str input-bundle)})]
          (if (#{201 200} (:status response))
            (status/send! (:agent-name agent-definition) "input-bundle" "sent" 1)
            (do
              (status/send! (:agent-name agent-definition) "input-bundle" "error" 1)
              (throw (new Exception (str "Failed to send to Percolator with status code: " (:status response) (:body response)))))))
      @retry-delay
      retries
      ; Only log info on retry because it'll be tried again.
      #(log/info "Error sending Input Bundle" (:id input-bundle) "with exception" (.getMessage %))
      ; But if terminate is called, that's a serious problem.
      #(do
        (status/send! (:agent-name agent-definition) "input-bundle" "abandoned" 1)
        (log/error "Failed to send Input Bundle" (:id input-bundle) "to Percolator"))
      #(log/info "Finished sending to Percolator"))
  (recur (<! input-bundle-chan)))))

(defn run
  [agent-definition]

  (check-definition! agent-definition)
  (start-heartbeat (:agent-name agent-definition) "heartbeat" "tick")
  (start-input-bundle-processing agent-definition)

  (log/info "Starting agent...")
  (doseq [schedule-item (:schedule agent-definition)]
    (log/info "Scheduling " (:name schedule-item) "every" (:seconds schedule-item) "seconds")
    (at-at/every (* 1000 (:seconds schedule-item))
                 (fn []
                   (try
                    (log/info "Execute schedule" (:name schedule-item))
                    (status/send! (:agent-name agent-definition) "schedule" (:name schedule-item) 1)
                    (let [artifacts (fetch-artifacts agent-definition (:required-artifacts schedule-item))]
                      ((:fun schedule-item) artifacts input-bundle-chan))
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
          ((:fun runner) {} input-bundle-chan))))))
