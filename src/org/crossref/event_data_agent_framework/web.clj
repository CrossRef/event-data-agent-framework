(ns org.crossref.event-data-agent-framework.web
  "Web client functions."
  (:require [clojure.tools.logging :as l]
            [clojure.string :as string])
  (:require [org.httpkit.client :as http]
            [config.core :refer [env]])
  (:import [org.jsoup Jsoup]
           [java.net URLDecoder URL MalformedURLException]))

(defn fetch-canonical-url
  "Fetch a canonical URL from the HTML metadata, if present.
   Follow redirects."
  [url]
  (let [body (:body @(http/get url {:follow-redirects true}))]
    (when body 
      (let [dom (Jsoup/parse body)
            links (.select dom "link[rel=canonical]")
            
            hrefs (keep #(.attr % "href") links)]
    (first hrefs)))))

(defn extract-a-hrefs-from-html
  "Extract all a-href links form an HTML body."
  [input]
    (let [links (-> input
            Jsoup/parse
            (.select "a[href]"))
          hrefs (keep #(.attr % "href") links)]
      (set hrefs)))

(defn href-to-url [text]
  "Convert an href from an <a>, as found in HTML, to a URL. Accepts protocol-relative URLs."
  (when text
    ; If we get non-URL things, like fragments, skip.
    (try 
    ; These will be found in a well-deliniated URL, so we can take the rest of the link.
    (when-let [url-text (cond
                    (.startsWith ^String text "//") (str "http:" text)
                    (.startsWith ^String text "http:") text
                    (.startsWith ^String text "https:") text
                    ; Ignore relative URLs, as they can't be DOIs or publisher links.
                    :default nil)]
      (new URL (URLDecoder/decode url-text "UTF-8")))
    (catch MalformedURLException e)
    (catch IllegalArgumentException e))))

(defn- is-doi-url?
  "If the URL is a DOI, return the DOI as a string."
  [url-string]

  (let [url (href-to-url url-string)]
    (when url 
      (let [host (.getHost ^URL url)
            url-path (.getPath ^URL url)
            ; Drop leading slash.
            url-path (when-not (string/blank? url-path) (subs url-path 1))
            likely-doi (and host
                            url-path
                            (.contains host "doi.org")
                            (.startsWith url-path "10."))]
        (when likely-doi url-path)))))

(defn extract-dois-from-body
  "Fetch the set of DOIs that are mentioned in the body text."
  [body]
  ; As some character encoding inconsistencies crop up from time to time in the live stream, this reduces the chance of error. 
  (let [hrefs (extract-a-hrefs-from-html body)
        dois (set (keep is-doi-url? hrefs))]
    dois))


(defn query-reverse-api
  "Query the reverse API with a URL or a snippet of text. Return DOI match (or otherwise) with version of service."
  [query]
  (let [url (str (:reverse-service-base env) "/guess-doi")
        response @(http/get url {:query-params {"q" query}})
        version (get-in response [:headers :x-version])
        doi (when (= 200 (:status response))
              (:body response))]
    {:doi doi :version version}))

(defn extract-dois-from-body-via-landing-page-urls
  "Fetch the set of DOIs that are mentioned in the body text by their landing page URLs.
  Return map of {candidate-url [doi-url service-version]}. doi can be nil if not matched."
  [domain-set body]
  ; As some character encoding inconsistencies crop up from time to time in the live stream, this reduces the chance of error. 
  (let [hrefs (extract-a-hrefs-from-html body)
        ; Just do a quick stupid filter to only query for hopeful domains.
        candidates (filter #(some (fn [domain] 
                                    (.contains % domain)) domain-set) hrefs)
        
        ; map of {url [doi service-version]}
        results (into {} (map (fn [url]
                         [url (query-reverse-api url)]))
                     candidates)]
    results))

