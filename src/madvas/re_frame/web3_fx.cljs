(ns madvas.re-frame.web3-fx
  (:require
    [cljs-web3.eth :as web3-eth]
    [cljs.spec.alpha :as s]
    [re-frame.core :refer [reg-fx dispatch console reg-event-db reg-event-fx]]))

(defn- blockchain-filter-opts? [x]
  (or (map? x) (string? x) (nil? x)))

(s/def ::instance (complement nil?))
(s/def ::db-path (s/coll-of keyword?))
(s/def ::dispatch vector?)
(s/def ::contract-fn-arg any?)
(s/def ::addresses (s/coll-of string?))
(s/def ::watch? boolean?)
(s/def ::blockchain-filter-opts blockchain-filter-opts?)
(s/def ::web3 (complement nil?))
(s/def ::event-ids sequential?)
(s/def ::f fn?)
(s/def ::args (s/coll-of ::contract-fn-arg))
(s/def ::on-success ::dispatch)
(s/def ::on-error ::dispatch)
(s/def ::on-tx-receipt ::dispatch)
(s/def ::tx-opts map?)
(s/def ::method keyword?)
(s/def ::event-name keyword?)
(s/def ::event-id any?)
(s/def ::event-filter-opts (s/nilable map?))
(s/def ::blockchain-filter-opts blockchain-filter-opts?)
(s/def ::transaction-hash string?)

(s/def :web3-fx.blockchain/fns
  (s/coll-of (s/nilable (s/or :params (s/cat :f fn?
                                             :args (s/* any?)
                                             :on-success ::on-success
                                             :on-error ::on-error)
                              :params (s/keys :req-un [::f]
                                              :opt-un [::args ::on-success ::on-error])))))

(s/def :web3-fx.contract.constant/fns
  (s/coll-of (s/nilable (s/or :params (s/cat :instance ::instance
                                             :method ::method
                                             :args (s/* ::contract-fn-arg)
                                             :on-success ::on-success
                                             :on-error ::on-error)
                              :params (s/keys :req-un [::instance ::method]
                                              :opt-un [::args ::on-success ::on-error])))))

(s/def :web3-fx.contract.state/fns
  (s/coll-of (s/nilable (s/or :params (s/cat :instance ::instance
                                             :method ::method
                                             :args (s/* ::contract-fn-arg)
                                             :tx-opts ::tx-opts
                                             :on-success ::on-success
                                             :on-error ::on-error
                                             :on-tx-receipt ::on-tx-receipt)
                              :params (s/keys :req-un [::instance ::method]
                                              :opt-un [::args ::tx-opts ::on-success ::on-error
                                                       ::on-tx-receipt])))))

(s/def ::events (s/coll-of (s/nilable (s/or :params (s/cat :instance ::instance
                                                           :event-id (s/? any?)
                                                           :event-name ::event-name
                                                           :event-filter-opts ::event-filter-opts
                                                           :blockchain-filter-opts ::blockchain-filter-opts
                                                           :on-success ::on-success
                                                           :on-error ::on-error)
                                            :params (s/keys :req-un [::instance ::event-name ::on-success]
                                                            :opt-un [::event-id ::event-filter-opts
                                                                     ::blockchain-filter-opts ::on-success ::on-error])))))


(s/def ::contract-events (s/keys :req-un [::events ::db-path]))
(s/def ::contract-events-stop-watching (s/keys :req-un [::event-ids ::db-path]))
(s/def ::contract-constant-fns (s/keys :req-un [:web3-fx.contract.constant/fns]))
(s/def ::contract-state-fns (s/keys :req-un [::web3 :web3-fx.contract.state/fns ::db-path]))
(s/def ::add-transaction-hash-to-watch (s/keys :req-un [::web3 ::db-path ::transaction-hash ::on-tx-receipt]))
(s/def ::blockchain-fns (s/keys :req-un [::web3 :web3-fx.blockchain/fns]))
(s/def ::balances (s/keys :req-un [::addresses ::on-success ::on-error ::web3]
                          :opt-un [::watch? ::db-path ::blockchain-filter-opts ::instance]))
(s/def ::blockchain-filter (s/keys :req-un [::db-path ::on-success ::on-error ::web3 ::blockchain-filter-opts]))

(defn- ensure-filter-params [event]
  (if (:event-id event)
    event
    (assoc event :event-id (:event-name event))))

(defn- dispach-fn [on-success on-error & args]
  (fn [err res]
    (if err
      (dispatch (vec (concat on-error (cons err args))))
      (dispatch (vec (concat on-success (cons res args)))))))

(defn- contract-event-dispach-fn [on-success on-error]
  (fn [err res]
    (if err
      (dispatch (vec (concat on-error [err])))
      (dispatch (vec (concat on-success [(:args res) res]))))))

(reg-event-db
  :web3-fx.contract/assoc-event-filters
  (fn [db [_ filters-db-path filters]]
    (update db filters-db-path merge filters)))

(defn- event-stop-watching! [db db-path event-id]
  (when-let [event-filter (get-in db (conj db-path event-id))]
    (web3-eth/stop-watching! event-filter (fn []))))

(reg-fx
  :web3-fx.contract/events
  (fn [raw-config]
    (let [{:keys [events db-path] :as config}
          (s/conform ::contract-events raw-config)]

      (if (= :cljs.spec/invalid config)
        (console :error (s/explain-str ::contract-events raw-config))
        (dispatch [:web3-fx.contract/events* config])))))

(reg-event-fx
  :web3-fx.contract/events*
  (fn [{:keys [db]} [_ config]]
    {:web3-fx.contract/events* (assoc config :db db)}))

(reg-fx
  :web3-fx.contract/events*
  (fn [{:keys [events db-path db] :as config}]
    (let [new-filters
          (->> events
            (remove nil?)
            (map second)
            (map ensure-filter-params)
            (reduce (fn [acc {:keys [event-id event-name instance on-success on-error
                                     event-filter-opts blockchain-filter-opts]}]
                      (event-stop-watching! db db-path event-id)
                      (assoc acc event-id (web3-eth/contract-call
                                            instance
                                            event-name
                                            event-filter-opts
                                            blockchain-filter-opts
                                            (contract-event-dispach-fn on-success on-error)))) {}))]
      (dispatch [:web3-fx.contract/assoc-event-filters db-path new-filters]))))

(reg-fx
  :web3-fx.contract/events-stop-watching
  (fn [raw-config]
    (let [{:keys [event-ids db-path] :as config}
          (s/conform ::contract-events-stop-watching raw-config)]

      (if (= :cljs.spec/invalid config)
        (console :error (s/explain-str ::contract-events-stop-watching raw-config))
        (dispatch [:web3-fx.contract/events-stop-watching* config])))))

(reg-event-fx
  :web3-fx.contract/events-stop-watching*
  (fn [{:keys [db]} [_ {:keys [event-ids db-path] :as config}]]
    {:db (apply update-in db db-path dissoc event-ids)
     :web3-fx.contract/events-stop-watching* (assoc config :db db)}))

(reg-fx
  :web3-fx.contract/events-stop-watching*
  (fn [{:keys [event-ids db-path db] :as config}]
    (doseq [event-id event-ids]
      (event-stop-watching! db db-path event-id))))

(reg-fx
  :web3-fx.contract/constant-fns
  (fn [raw-params]
    (let [{:keys [fns] :as params} (s/conform ::contract-constant-fns raw-params)]
      (if (= :cljs.spec/invalid params)
        (console :error (s/explain-str ::contract-constant-fns raw-params))
        (doseq [{:keys [method instance args on-success on-error]} (map second (remove nil? fns))]
          (apply web3-eth/contract-call (concat [instance method]
                                                args
                                                [(dispach-fn on-success on-error)])))))))


(defn- remove-blockchain-filter! [db filter-db-path]
  (when-let [blockchain-filter (get-in db filter-db-path)]
    (web3-eth/stop-watching! blockchain-filter (fn [])))
  (assoc-in db filter-db-path nil))

(reg-event-fx
  :web3-fx.contract/transaction-receipt-loaded
  (fn [{:keys [db]} [_ [tx-hashes-db-path filter-db-path] transaction-hash receipt on-transaction-receipt]]
    (when (get-in db (conj tx-hashes-db-path transaction-hash))
      (let [rest-tx-hashes (dissoc (get-in db tx-hashes-db-path) transaction-hash)]
        {:dispatch (vec (concat on-transaction-receipt [receipt]))
         :db (cond-> db
               (empty? rest-tx-hashes)
               (remove-blockchain-filter! filter-db-path)

               true
               (assoc-in tx-hashes-db-path rest-tx-hashes))}))))

(reg-event-db
  :web3-fx.contract/add-transaction-hash-to-watch
  (fn [db [_ {:keys [web3 db-path transaction-hash on-tx-receipt] :as args}]]
    (when-not (s/valid? ::add-transaction-hash-to-watch args)
      (console :error (s/explain-str ::add-transaction-hash-to-watch args)))
    (let [tx-hashes-db-path (conj db-path :transaction-hashes)
          filter-db-path (conj db-path :filter)
          all-tx-hashes (assoc (get-in db tx-hashes-db-path) transaction-hash on-tx-receipt)]

      (remove-blockchain-filter! db filter-db-path)

      (-> db
        (assoc-in filter-db-path
                  (web3-eth/filter
                    web3
                    "latest"
                    (fn [err]
                      (when-not err
                        (doseq [[tx-hash on-tx-receipt] all-tx-hashes]
                          (web3-eth/get-transaction-receipt
                            web3
                            tx-hash
                            (fn [_ receipt]
                              (when (:block-hash receipt)
                                (dispatch [:web3-fx.contract/transaction-receipt-loaded
                                           [tx-hashes-db-path filter-db-path]
                                           tx-hash
                                           receipt
                                           on-tx-receipt])))))))))

        (assoc-in tx-hashes-db-path all-tx-hashes)))))

(defn- create-state-fn-callback [web3 db-path on-success on-error on-tx-receipt]
  (fn [err transaction-hash]
    (if err
      (dispatch (conj on-error err))
      (do
        transaction-hash
        (dispatch (conj on-success transaction-hash))
        (dispatch [:web3-fx.contract/add-transaction-hash-to-watch
                   {:web3 web3
                    :db-path db-path
                    :transaction-hash transaction-hash
                    :on-tx-receipt on-tx-receipt}])))))

(reg-fx
  :web3-fx.contract/state-fns
  (fn [raw-params]
    (let [{:keys [web3 db-path fns] :as params} (s/conform ::contract-state-fns raw-params)]
      (if (= :cljs.spec/invalid params)
        (console :error (s/explain-str ::contract-state-fns raw-params))
        (doseq [{:keys [method instance args tx-opts on-success on-error on-tx-receipt]}
                (map second (remove nil? fns))]
          (apply web3-eth/contract-call
                 (concat [instance method]
                         args
                         [tx-opts]
                         [(create-state-fn-callback web3 db-path on-success on-error on-tx-receipt)])))))))

(reg-fx
  :web3-fx.blockchain/fns
  (fn [raw-params]
    (let [{:keys [fns] :as params} (s/conform ::blockchain-fns raw-params)]
      (if (= :cljs.spec/invalid params)
        (console :error (s/explain-str ::blockchain-fns raw-params))
        (doseq [{:keys [f args on-success on-error]} (map second (remove nil? fns))]
          (apply f (concat [(:web3 params)] args [(dispach-fn on-success on-error)])))))))

(reg-event-db
  :web3-fx.blockchain/add-addresses-to-watch
  (fn [db [_ web3 db-path addresses blockchain-filter-opts on-success on-error]]
    (let [addresses-db-path (conj db-path :addresses)
          filter-db-path (conj db-path :filter)
          all-addresses (set (concat (get-in db addresses-db-path)
                                     addresses))]

      (when-let [blockchain-filter (get-in db filter-db-path)]
        (web3-eth/stop-watching! blockchain-filter (fn [])))

      (let [blockchain-filter
            (web3-eth/filter
              web3
              blockchain-filter-opts
              (fn [err _]
                (if err
                  (dispatch (conj on-error err))
                  (doseq [address all-addresses]
                    (web3-eth/get-balance web3 address (dispach-fn on-success on-error address))))))]

        (-> db
          (assoc-in addresses-db-path all-addresses)
          (assoc-in filter-db-path blockchain-filter))))))

(reg-event-fx
  :web3-fx.blockchain.erc20/balances-of
  (fn [db [_ {:keys [instance on-success on-error addresses]}]]
    {:web3-fx.contract/constant-fns
     {:fns (for [address addresses]
             [instance
              :balance-of
              address
              [:web3-fx.blockchain.erc20/balance-loaded {:address    address
                                                         :instance   instance
                                                         :on-success on-success}]
              (conj on-error address)])}}))

(reg-event-fx                                               ;; To keep it consisted with eth balance result order
  :web3-fx.blockchain.erc20/balance-loaded
  (fn [db [_ {:keys [address instance on-success]} balance]]
    {:dispatch (vec (concat on-success [balance address instance]))}))

(reg-event-fx
  ;; Instead of setting up 2 events per address, would be better to use web3 topic filtering, but didn't work when I tried
  :web3-fx.blockchain.erc20/add-addresses-to-watch
  (fn [db [_ {:keys [db-path instance blockchain-filter-opts addresses on-success on-error] :as config}]]
    (let [instance-address (aget instance "address")]
      (if instance-address
        {:web3-fx.contract/events
         {:db-path db-path
          :events (apply concat
                         (for [address addresses]
                           (let [base-event-id (str instance-address "-" address)
                                 disp [:web3-fx.blockchain.erc20/on-transfer address config]]
                             [[instance (str base-event-id "-transfer-from")
                               :Transfer
                               {:from address}
                               blockchain-filter-opts
                               disp
                               (conj on-error address)]
                              [instance (str base-event-id "-transfer-to")
                               :Transfer
                               {:to address}
                               blockchain-filter-opts
                               disp
                               (conj on-error address)]])))}}))))

(reg-event-fx
  :web3-fx.blockchain.erc20/on-transfer
  (fn [db [_ address config]]
    {:dispatch [:web3-fx.blockchain.erc20/balances-of (assoc config :addresses [address])]}))

(reg-fx
  :web3-fx.blockchain/balances
  (fn [{:keys [addresses web3 on-success on-error watch? db-path blockchain-filter-opts instance] :as config}]
    (s/assert ::balances config)
    (if-not instance
      (doseq [address addresses]
        (web3-eth/get-balance web3 address (dispach-fn on-success on-error address)))
      (dispatch [:web3-fx.blockchain.erc20/balances-of config]))
    (when (and watch? (seq addresses))
      (if-not instance
        (dispatch [:web3-fx.blockchain/add-addresses-to-watch
                   web3
                   db-path
                   addresses
                   blockchain-filter-opts
                   on-success
                   on-error])
        (dispatch [:web3-fx.blockchain.erc20/add-addresses-to-watch config])))))

(reg-event-db
  :web3-fx.blockchain/add-filter
  (fn [db [_ web3 db-path blockchain-filter-opts on-success on-error]]

    (when-let [blockchain-filter (get-in db db-path)]
      (web3-eth/stop-watching! blockchain-filter (fn [])))

    (let [blockchain-filter
          (web3-eth/filter
            web3
            blockchain-filter-opts
            (fn [err res]
              (if err
                (dispatch (conj on-error err))
                (dispatch (conj on-success res)))))]
      (assoc-in db db-path blockchain-filter))))

(reg-event-db
  :web3-fx.blockchain/remove-filter
  (fn [db [_ db-path]]
    (when-let [blockchain-filter (get-in db db-path)]
      (web3-eth/stop-watching! blockchain-filter (fn [])))))

(reg-fx
  :web3-fx.blockchain/filter
  (fn [{:keys [web3 db-path blockchain-filter-opts on-success on-error] :as config}]
    (s/assert ::blockchain-filter config)
    (dispatch [:web3-fx.blockchain/add-filter web3 db-path blockchain-filter-opts on-success on-error])))

(reg-fx
  :web3-fx.blockchain/filter-stop-watching
  (fn [db-path]
    (s/assert ::db-path db-path)
    (dispatch [:web3-fx.blockchain/remove-filter db-path])))
