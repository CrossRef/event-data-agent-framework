(ns org.crossref.event-data-agent-framework.core-test
  (:require [clojure.test :refer :all]
            [org.crossref.event-data-agent-framework.core :as core]))

(defn dummy-f [] nil)

(deftest check-definition-test
  "Definition check tests for required fields."
    
  (let [correct {:agent-name "Test Agent"
                 :schedule [{:fun dummy-f :seconds 5 :name "Dummy Schedule Function"}]
                 :runners [{:fun dummy-f :name "Dummy Runner Function"}]
                 :build-evidence dummy-f
                 :process-evidence dummy-f}]
    
    (testing "Correct object OK"
      (is (empty? (core/check-definition correct))))
    
    (doseq [field-name [:agent-name :schedule :runners :build-evidence :process-evidence]]
      (testing (str "Missing field " field-name)
        (is (not-empty (core/check-definition (dissoc correct field-name)))))))) 