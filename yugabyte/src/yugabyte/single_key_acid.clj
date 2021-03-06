(ns yugabyte.single-key-acid
  (:require [clojure [pprint :refer :all]]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen [client    :as client]
                    [checker   :as checker]
                    [generator :as gen]
                    [independent :as independent]]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model]
            [clojurewerkz.cassaforte [client :as cassandra]
                                     [query :refer :all]
                                     [policies :refer :all]
                                     [cql :as cql]]
            [yugabyte [client :as c]]))

(def keyspace "jepsen")
(def table-name "single_key_acid")

(c/defclient CQLSingleKey keyspace []
  (setup! [this test]
    (c/create-table conn table-name
                    (if-not-exists)
                    (column-definitions {:id :int
                                         :val :int
                                         :primary-key [:id]})))

  (invoke! [this test op]
    (c/with-errors op #{:read}
      (let [[id val] (:value op)]
        (case (:f op)
          :write
          (do (cql/insert-with-ks conn keyspace table-name
                                  {:id id, :val val})
              (assoc op :type :ok))

          :cas
          (let [[expected-val new-val] val
                res (cql/update-with-ks conn keyspace table-name
                                        {:val new-val}
                                        (only-if [[= :val expected-val]])
                                        (where [[= :id id]]))
                applied (get (first res) (keyword "[applied]"))]
            (assoc op :type (if applied :ok :fail)))

          :read
          (let [value (->> (cql/select-with-ks conn keyspace table-name
                                               (where [[= :id id]]))
                           first
                           :val)]
            (assoc op :type :ok :value (independent/tuple id value)))))))

  (teardown! [this test]))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn workload
  [opts]
  (let [n (count (:nodes opts))]
    {:client (->CQLSingleKey)
     :generator (independent/concurrent-generator
                  (* 2 n)
                  (range)
                  (fn [k]
                    (->> (gen/reserve n (gen/mix [w cas cas]) r)
                         (gen/stagger 1)
                         (gen/process-limit 20))))
     :checker (independent/checker
                (checker/compose
                  {:timeline (timeline/html)
                   :linear   (checker/linearizable
                               {:model (model/cas-register 0)})}))}))
