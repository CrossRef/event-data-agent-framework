# Crossref Event Event Data Agent Framework

<img src="doc/logo.png" align="right" style="float: right">

This is a framework to help you write an Agent within the Event Data system. You're free to write any agent any way you want, but this framework takes advantage of the Crossref Event Data services, such as Artifacts and the Percolator system. An Agent written with this framework doesn't actually do much heavy-lifting at all: it simply collects data from somewhere, packages it up into an Input Bundle, and passes it to the Percolator. The Percolator does the rest: extracting DOIs from various inputs, fetching webpages, verifying DOIs, submitting Evidence Records and Agents, etc.

If you want to write an Agent, you should familiarise yourself with the Percolator's Input Bundle format.

This Framework allows an Agent to describe itself in terms of 'schedule' and 'runner' activities. Schedule activities are run on a schedule, runners are run on agent start-up, and restarted if they stop. The Agent supplies a list of required Artifacts, and these are retrieved by the Framework and passed to the schedule or runner function.

## Patterns

This allows the implementation of several patterns:

### Scheduled artifact scan

Reddit Agent: Sheduled scan, getting a copy of the domain list Artifact each time, then iterating over every domain, making a query for each. 

Newsfeed Agent: Scheduled scan, getting a copy of the RSS Feed Artifact each time, iterating over every newsfeed.

### Stream Adaptor

Twitter: Connect to the Twitter streaming API, transform each input into an Action in an input bundle, send that on.

Wikipedia: Connect to the Wikipedia Event Stream, transform ecah input into an Action in an input bundle, send that on.

## To write an Agent

Create a `definition` hashmap and pass it to the `org.crossref.event-data-agent-framework.core/run` function. Definition should be an object with the following fields:

   - `:agent-name`
   - `:schedule` : A seq of `{:fun :seconds :name :required-artifacts}` hashmaps. The function will be run on the schedule and reported via the Status Service. The function takes a channel onto which complete Input Packages should be sent.
   - `:runners` : A seq of `{:fun :name :required-artifacts}` hashmaps. Each function will be run in a thread and the Health Service will be notified of a heartbeat as long as it keeps running. The function takes a channel onto which complete Input Packages should be sent.

## Reporting

The Agent automatically reports to the Health Service. Given the configuraion of «agent-name», it logs:

 - `«agent-name»/service/running` - logs all the time that the service is running, once per minute.
 - `«agent-name»/artifact/«artifact-name»` - logs 1 every time an artifact of the given name is fetched.
 - `«agent-name»/«scheduled-f-name»/start` - logs 1 every time a scheduled function is run.
 - `«agent-name»/«scheduled-f-name»/stop` - logs 1 every time a scheduled function stops running.
 - `«agent-name»/«runner-f-name»/heartbeat` - logs once a minute for the duration that a run function is run until it dies (which it never should).

## Running

The following environment variables should be set:

  - `PERCOLATOR_URL_BASE` - URL of Percolator service base with no trailing slash, e.g. `http://localhost:8006` or `https://percolator.eventdata.crossref.org`
  - `STATUS_SERVICE_BASE` - URL of Status service, e.g. `http://localhost:8003` or `https://status.eventdata.crossref.org`
  - `JWT_TOKEN` - JWT authorized to deposit events with the source name that this agent produces.
  
An Agent should start itself by calling `org.crossref.event-data-agent-framework.core/run` with its agent definition.

## External Services

This needs an instance of the Percolator Service and the Status Service, which must be provided as configuration. 

## Development

To use use a local repository when developing new functionality against agents:

    lein test && lein uberjar && rm -rf ~/.m2/repository/org.crossref && lein localrepo install target/uberjar/event-data-agent-framework-0.1.0-SNAPSHOT.jar org.crossref/event-data-agent-framework "0.1.0-SNAPSHOT"
