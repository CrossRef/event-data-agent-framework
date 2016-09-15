(ns org.crossref.event-data-agent-framework.core
  (:require [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [clojure.core.async :refer [thread]])
  (:require [overtone.at-at :as at-at]
            [org.httpkit.client :as client]
            [config.core :refer [env]]
            [robert.bruce :refer [try-try-again]])
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
      (when-not (:version input) ["Missing version"])
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

(defn send-heartbeat [heartbeat-name heartbeat-count]
  (try 
    (try-try-again {:sleep 10000 :tries 10}
       #(let [result @(client/post (str (:status-service-base env) (str "/status/" heartbeat-name))
                       {:headers {"Content-type" "text/plain" "Authorization" (str "Token " (:status-service-auth-token env))}
                        :body (str heartbeat-count)})]
         (when-not (= (:status result) 201)
           (log/error "Can't send heartbeat, status" (:status result)))))
   (catch Exception e (log/error "Can't send heartbeat, exception:" e))))

(defn start-heartbeat
  "Schedule a named heartbeat with the Status server to trigger once a minute."
  [heartbeat-name]
  (at-at/every 60000 #(send-heartbeat heartbeat-name 1) schedule-pool))


(defn fetch-artifact
  "Download artifact to temp file. Return [version-url temp-file] or nil.
  File must be deleted after use."
  [agent-definition artifact-name]
  (send-heartbeat (str (:agent-name agent-definition) "/artifact/fetch") 1)
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

(defn send-evidence-callback
  "Receive an Evidence record input as a JSON-serializable Clojure structure. Passed as a callback."
  [agent-definition input]
    (log/info "Posting evidence")
    (try 
    (let [result @(client/post (str (:evidence-service-base env) "/evidence")
                             {:headers {"Content-type" "application/json" "Authorization" (str "Token " (:evidence-service-auth-token env))}
                              :body (json/write-str input)
                              :follow-redirects false})]
      (log/info "Posted evidence, got response" (:status result) (:headers result))
      (send-heartbeat (str (:agent-name agent-definition) "/evidence/sent") 1)
      ; Correct response is redirect to new resource.
      (if (= (:status result) 303)
        (do
          (send-heartbeat (str (:agent-name agent-definition) "/evidence/sent-ok") 1)
          true)
        (do
          (send-heartbeat (str (:agent-name agent-definition) "/evidence/sent-error") 1)
          (log/error "Can't send Evidence, status" (:status result))
          false)))
    (catch Exception e (log/error "Can't send Evidence, exception:" e))))
  

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
                    (send-heartbeat (str (:agent-name agent-definition) "/input/" (:name schedule-item)) 1)
                    (let [artifacts (fetch-artifacts agent-definition (:required-artifacts schedule-item))]
                      
                      ; Call the schedule function with the requested 
                      ((:fun schedule-item) artifacts (partial send-evidence-callback agent-definition))
                      
                      (doseq [[_ [_ artifact-file]] artifacts]
                        (log/info "Deleting temporary artifact file" artifact-file)
                        (.delete artifact-file)))
                    (catch Exception e (log/error "Error in schedule" e))))
                 schedule-pool :fixed-delay true))
  
  ; NB doesn't yet fetch artifacts.
  (doseq [runner (:runners agent-definition)]
    (log/info "Starting Agent runner" (:name runner))
    (let [num-threads (get runner :threads 1)]
      (dotimes [t num-threads]
        (log/info "Starting thread" t "for" (:name runner))
        (thread
          ((:fun runner) {} (partial send-evidence-callback agent-definition)))))))

(defn run-process
  [agent-definition]
  (check-definition! agent-definition)
  (start-heartbeat (str (:agent-name agent-definition) "/process/heartbeat")))

(defn run  
  [args agent-definition]
  (log/info "Starting agent...")
  (let [cmd (first args)]
    (condp = cmd
      "ingest" (run-ingest agent-definition)
      "process" (run-process agent-definition))))
