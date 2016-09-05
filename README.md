# Crossref Event Event Data Agent Framework

Framework for writing Agents for Crossref Event Data. It allows construciton of an Agent that follows the CED conventions:

 - takes input events from some external source
 - uses appropriate Artifacts
 - builds an Evidence Record
 - sends Evidence Record to Evidence Service
 - reports status to Status Service

The Framework provides:

a framework with hooks for:

 - recieving internal data
 - providing identifiers of required Artifacts
 - functions for building Evidence from the input
 - functions for building state from the input

it also provides:

 - service runner
 - reporting of all functions to the Status Service
 - sending Evidence Records
 - test harness for re-running the input on a given piece of Evidence from STDIN

## To write an Agent

 - Add a dependency to `event-data-agent-framework.declaration`.
 - Set the `proj.clj` to `:main ^:skip-aot event-data-agent-framework.core`.
 - Include a namespace called `agent`, with a variable called `definition`, i.e. `agent/definition`.

Definition should be an object with the following fields:

   - `:agent-name`
   - `:required-artifacts` : A seq of artifact names, e.g. `["domain-list"]`. The content is made available in the context.
   - `:schedule` : A seq of `{:fun :seconds :name}` hashmaps. The function will be run on the schedule and reported via the Status Service. The function takes a context object and should return a number which will be sent to the Health Service.
   - `:runners` : A seq of `{:fun :name}` hashmaps. Each function will be run in a thread and the Health Service will be notified of a heartbeat as long as it keeps running.
   - `:build-evidence` : A function that accepts a piece of input and returns a hashmap of an Evidence Record partial `{:state :working}`
   - `:process-evidence` : A function that accepts a partial Evidence Record partial with `{:artifacts :state :working}` and produces a seq of Events.  

## Reporting

The Agent automatically reports to the Health Service. Given the configuraion of «agent-name», it logs:

 - `«agent-name»/service/running` - logs all the time that the service is running, once per minute.
 - `«gaent-name»/artifact/«artifact-name»` - logs 1 every time an artifact of the given name is fetched.
 - `«agent-name»/«scheduled-f-name»/start` - logs 1 every time a scheduled function is run.
 - `«agent-name»/«scheduled-f-name»/stop` - logs 1 every time a scheduled function stops running.
 - `«agent-name»/«runner-f-name»/heartbeat` - logs once a minute for the duration that a run function is run until it dies (which it never should).
 - `«agent-name»/evidence/input` - logs 1 every time a new input is received
 - `«agent-name»/evidence/created` - logs 1 every time a new piece of evidence is created.
 - `«agent-name»/evidence/sent` - logs 1 every time a new piece of evidence is sent.
 - `«agent-name»/external/evidence-service-query` - logs 1 every time the Evidence Service is queried.
 - `«agent-name»/external/evidence-service-deposit` - logs 1 every time data is sent to the Evidence Service.


## Running

The following config values should be set:

  `EVIDENCE_SERVICE` - comma-separated URL endpoints, e.g. `"http://evidence.eventdata.crossref.org,http://192.168.0.1:8765"
  `STATUS_SERVICE` - comma-separated URL endpoints, e.g. `"http://status.eventdata.crossref.org,http://192.168.0.1:8765"
  `INTERNAL_AUTH_TOKEN` - auth token for use with Evidence Service and Status Service.

An Agent that uses this framework should be run:

    lein run service

To reprocess an Evidence Record, taking an Evidence Record as STDIN and returning the Evidence Record with Events reconstructed:

    cat input.json | lein run process-evidence > output.json

## External Service

This needs an instance of the Evidence Service and the Status Service, which must be provided as configuration. In the case of service unavailability, these will be automatically round-robinned.

