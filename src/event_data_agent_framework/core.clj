(ns event-data-agent-framework.core
  (:require [clojure.tools.logging :refer [trace debug info warn error fatal]])
  (:gen-class))

(defn check-definition
  "Checks a definition, returns a list of errors or an empty list for OK."
  [input]
  (concat
    (when-not (:agent-name input) ["Missing agent-name"])
    (when-not (:required-artifacts input) ["Missing required-artifacts"])
    (when-not (:schedule input) ["Missing schedule"])
    (when-not (:runners input) ["Missing runners"])
    (when-not (:build-evidence input) ["Missing build-evidence"])
    (when-not (:process-evidence input) ["Missing process-evidence"])))

(defn load-definition
  "Locate and retrieve the Agent definition."
  []

  (when-not (find-ns `agent)
    (fatal "Cannot find agent definition namespace, exiting.")
      (System/exit 1))
  
  (when-not (find-var 'agent/definition)
    (fatal "Cannot find agent definition, exiting.")
    (System/exit 1))
    
  (let [definition (var-get (find-ns 'agent/definition))]
    (when-not definition
      (fatal "Agent definition empty, exiting.")
      (System/exit 1)
      
      (when-let [definition-errors (check-definition definition)]
        (doseq [er definition-errors]
          (error "Agent error: " er))
        (fatal "Agent definition incorrect, exiting.")
        (System/exit 1))
      
      definition)))

(defn run-agent
  []
  (let [definition (load-definition)])
  
  )

(defn -main
  "I don't do a whole lot ... yet."
  [cmd & args]
  (condp = cmd
    "agent" (run-agent)))
  