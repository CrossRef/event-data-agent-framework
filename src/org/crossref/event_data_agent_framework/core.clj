(ns org.crossref.event-data-agent-framework.core
  (:require [clojure.tools.logging :as log])
  (:require [overtone.at-at :as at-at]
            [org.httpkit.client :as client]
            [config.core :refer [env]])
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
      ; (when-not (:required-artifacts input) ["Missing required-artifacts"])
      (when-not (:schedule input) ["Missing schedule"])
      (when-not (:runners input) ["Missing runners"])
      (when-not (:build-evidence input) ["Missing build-evidence"])
      (when-not (:process-evidence input) ["Missing process-evidence"]))))

(defn check-definition!
  "Check a definition, log and exit if there are errors."
  [definition]
  (when-let [definition-errors (check-definition definition)]
    (doseq [er definition-errors]
      (log/error "Agent error: " er))
    (log/fatal "Agent definition incorrect, exiting.")
    (System/exit 1)))


(def schedule-pool (at-at/mk-pool))

(defn send-heartbeat
  "Send a named heartbeat to the Status server."
  [heartbeat-name]
  (try 
    ; TODO round-robin status servers. 
    (let [result @(client/post (str (:status-service-base env) (str "/status/" heartbeat-name))
                             {:headers {"Content-type" "text/plain" "Authorization" (str "Token " (:status-service-auth-token env))}
                              :body "1"})]
      (when-not (= (:status result) 201)
        (log/error "Can't send heartbeat, status" (:status result))))
    (catch Exception e (log/error "Can't send heartbeat, exception:" e))))

(defn start-heartbeat
  "Schedule a named heartbeat with the Status server to trigger once a minute."
  [heartbeat-name]
  (at-at/every 60000 #(send-heartbeat heartbeat-name) schedule-pool))


(defn fetch-artifact
  "Download artifact to temp file. Return [version-url temp-file] or nil.
  File must be deleted after use."
  [agent-definition artifact-name]
  (send-heartbeat (str (:agent-name agent-definition) "/artifact/fetch"))
  (let [; Resolve the current artifact
        latest-artifact-url (new URL (str (:evidence-service-base env) "/artifacts/" artifact-name "/current"))
        
        ; This is the persistent URL for the Artifact. We need this so we know what version we got.
        current-artifact-url (-> (client/get (str (:evidence-service-base env) "/artifacts/" artifact-name "/current") {:follow-redirects false})
                                 deref
                                 :headers
                                 :location)
        
        ^File temp-file (File/createTempFile "artifact" ".tmp")]
    (with-open [current-artifact-data (-> (client/get current-artifact-url {:as :stream}) deref :body)
                ^ReadableByteChannel channel (Channels/newChannel current-artifact-data)
                ^FileOutputStream output-stream (new FileOutputStream temp-file)]
      (log/info "Fetch artifact" artifact-name "from" latest-artifact-url "into" (str temp-file))
      (.transferFrom (.getChannel output-stream) channel 0 Long/MAX_VALUE))

    [current-artifact-url temp-file]))

(defn fetch-artifacts
  "Download seq of artifact names into a map of {artifact-name [version-url temp-file]}.
  Files must be deleted after use."
  [agent-definition artifact-names]
  (into {} (map (fn [artifact-name]
                  [artifact-name (fetch-artifact agent-definition artifact-name)]) artifact-names)))

(defn receive-input
  "Receive an input as a JSON-serializable Clojure structure. Passed as a callback.
  Should be in the skeleton of an Evidence Record. Can contain:
  :input 
  :artifacts"
  [input]
  ; TODO
  (prn "GOT INPUT CALLBACK" input))

(defn run-ingest
  [agent-definition]
  (check-definition! agent-definition)
  (start-heartbeat (str (:agent-name agent-definition) "/ingest/heartbeat"))
  
  (doseq [schedule-item (:schedule agent-definition)]
    (log/info "Scheduling " (:name schedule-item) "every" (:seconds schedule-item) "seconds")
    (at-at/every (* 1000 (:seconds schedule-item))
                 (fn []
                   (try
                    (log/info "Execute schedule" (:name schedule-item))
                    (send-heartbeat (str (:agent-name agent-definition) "/input/" (:name schedule-item)))
                    (let [artifacts (fetch-artifacts agent-definition (:required-artifacts schedule-item))]
                      ; Call the schedule function with the requested 
                      ((:fun schedule-item) artifacts receive-input)
                      
                      (doseq [[_ [_ artifact-file]] artifacts]
                        (log/info "Deleting temporary artifact file" artifact-file)
                        (.delete artifact-file)))
                    (catch Exception e (log/error "Error in schedule" e))))
                 schedule-pool)))

(defn run-process
  [agent-definition]
  (check-definition! agent-definition)
  (start-heartbeat (str (:agent-name agent-definition) "/process/heartbeat")))

(defn run  
  [args agent-definition]
  (let [cmd (first args)]
    (condp = cmd
      "ingest" (run-ingest agent-definition)
      "process" (run-process agent-definition))))
