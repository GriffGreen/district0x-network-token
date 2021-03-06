(ns contribution.events
  (:require
    [ajax.core :as ajax]
    [akiroz.re-frame.storage :as re-frame-storage]
    [cljs-time.coerce :as time-coerce]
    [cljs-time.core :as t]
    [cljs-web3.core :as web3]
    [cljs-web3.eth :as web3-eth]
    [cljs-web3.personal :as web3-personal]
    [cljs-web3.utils :as web3-utils]
    [cljs.spec :as s]
    [clojure.data :as data]
    [clojure.set :as set]
    [clojure.string :as string]
    [contribution.constants :as constants]
    [contribution.api :refer [parse-get-contrib-period contrib-period-args parse-get-configuration]]
    [district0x.big-number :as bn]
    [district0x.events :refer [get-contract get-instance all-contracts-loaded?]]
    [district0x.utils :as u]
    [goog.string :as gstring]
    [goog.string.format]
    [medley.core :as medley]
    [re-frame.core :as re-frame :refer [reg-event-db reg-event-fx inject-cofx path trim-v after debug reg-fx console dispatch]]))

(def interceptors district0x.events/interceptors)

(reg-event-fx
  :update-now
  interceptors
  (fn [{:keys [db]}]
    {:db (assoc db :now (t/now))}))

(reg-event-fx
  :deploy-all-contracts
  interceptors
  (fn [{:keys [db]} [contribution-args address-index]]
    (let [contribution (get-contract db :contribution)]
      {:web3-fx.blockchain/fns
       {:web3 (:web3 db)
        :fns [[web3-eth/contract-new
               (:abi contribution)
               (:contribution/owners contribution-args)
               (:contribution/required-count contribution-args)
               (:contribution/wallet contribution-args)
               (:contribution/founder1 contribution-args)
               (:contribution/founder2 contribution-args)
               (:contribution/early-sponsor contribution-args)
               (:contribution/advisers contribution-args)
               {:gas 4500000
                :data (:bin contribution)
                :from (if address-index
                        (nth (:my-addresses db) address-index)
                        (:active-address db))}
               [:contribution-contract-deployed address-index]
               [:district0x.log/error :deploy-all-contracts]]]}})))

(reg-event-fx
  :contribution-contract-deployed
  [interceptors (inject-cofx :localstorage)]
  (fn [{:keys [db localstorage]} [address-index instance]]
    (when-let [contract-address (aget instance "address")]
      (console :log :contribution " deployed at " contract-address)
      {:db (update-in db [:smart-contracts :contribution] merge {:address contract-address :instance instance})
       :localstorage (assoc-in localstorage [:smart-contracts :contribution] {:address contract-address})
       :dispatch [:deploy-mini-me-token-factory-contract address-index]})))

(reg-event-fx
  :deploy-mini-me-token-factory-contract
  interceptors
  (fn [{:keys [db]} [address-index]]
    (let [mini-me-token-factory (get-contract db :mini-me-token-factory)]
      {:web3-fx.blockchain/fns
       {:web3 (:web3 db)
        :fns [[web3-eth/contract-new
               (:abi mini-me-token-factory)
               {:gas 3000000
                :data (:bin mini-me-token-factory)
                :from (if address-index
                        (nth (:my-addresses db) address-index)
                        (:active-address db))}
               [:mini-me-token-factory-deployed address-index]
               [:district0x.log/error :deploy-mini-me-token-factory-contract]]]}})))

(reg-event-fx
  :mini-me-token-factory-deployed                           ; TODO generalize *-deployed event
  [interceptors (inject-cofx :localstorage)]
  (fn [{:keys [db localstorage]} [address-index instance]]
    (when-let [contract-address (aget instance "address")]
      (console :log :mini-me-token-factory " deployed at " contract-address)
      {:db (update-in db [:smart-contracts :mini-me-token-factory] merge {:address contract-address :instance instance})
       :localstorage (assoc-in localstorage [:smart-contracts :mini-me-token-factory] {:address contract-address})
       :dispatch [:deploy-dnt-token-contract address-index]})))

(reg-event-fx
  :deploy-dnt-token-contract
  interceptors
  (fn [{:keys [db]} [address-index]]
    (let [dnt-token (get-contract db :dnt-token)
          contribution-address (:address (get-contract db :contribution))
          mini-me-token-factory-address (:address (get-contract db :mini-me-token-factory))]
      {:web3-fx.blockchain/fns
       {:web3 (:web3 db)
        :fns [[web3-eth/contract-new
               (:abi dnt-token)
               contribution-address
               mini-me-token-factory-address
               {:gas 3300000
                :data (:bin dnt-token)
                :from (if address-index
                        (nth (:my-addresses db) address-index)
                        (:active-address db))}
               [:dnt-token-contract-deployed address-index]
               [:district0x.log/error :deploy-dnt-token-contract]]]}})))

(reg-event-fx
  :dnt-token-contract-deployed
  [interceptors (inject-cofx :localstorage)]
  (fn [{:keys [db localstorage]} [address-index instance]]
    (when-let [contract-address (aget instance "address")]
      (console :log :dnt-token " deployed at " contract-address)
      {:db (update-in db [:smart-contracts :dnt-token] merge {:address contract-address :instance instance})
       :localstorage (assoc-in localstorage [:smart-contracts :dnt-token] {:address contract-address})
       :dispatch [:contribution/set-district0x-network-token address-index]})))

(reg-event-fx
  :deploy-multisig-wallet
  interceptors
  (fn [{:keys [db]} [{:keys [:multisig-wallet/owners :multisig-wallet/required]} address-index]]
    (let [multisig-wallet (get-contract db :multisig-wallet)]
      {:web3-fx.blockchain/fns
       {:web3 (:web3 db)
        :fns [[web3-eth/contract-new
               (:abi multisig-wallet)
               owners
               required
               {:gas 3300000
                :data (:bin multisig-wallet)
                :from (if address-index
                        (nth (:my-addresses db) address-index)
                        (:active-address db))}
               [:district0x.log/info]
               [:district0x.log/error :deploy-multisig-wallet]]]}})))

(reg-event-fx
  :contribution/set-district0x-network-token
  interceptors
  (fn [{:keys [db]} [address-index]]
    (let [dnt-address (:address (get-contract db :dnt-token))]
      {:dispatch [:district0x.contract/state-fn-call
                  {:contract-key :contribution
                   :contract-method :set-district0x-network-token
                   :args [dnt-address]
                   :transaction-opts {:gas 300000
                                      :from (if address-index
                                              (nth (:my-addresses db) address-index)
                                              (:active-address db))}}]})))

(reg-event-fx
  :contribution/setup-event-listeners
  interceptors
  (fn [{:keys [db]}]
    (let [contribution-instance (get-instance db :contribution)]
      {:web3-fx.contract/events
       {:db-path [:web3-event-listeners]
        :events [[contribution-instance :on-contribution {:contrib-period-index constants/current-contrib-period}
                  "latest" :contribution/on-contribution [:district0x.log/error :on-contribution]]
                 [contribution-instance :on-soft-cap-reached {:contrib-period-index constants/current-contrib-period}
                  "latest" :contribution/on-soft-cap-reached [:district0x.log/error :on-soft-cap-reached]]
                 [contribution-instance :on-hard-cap-reached {:contrib-period-index constants/current-contrib-period}
                  "latest" :contribution/on-hard-cap-reached [:district0x.log/error :on-hard-cap-reached]]
                 [contribution-instance :on-emergency-changed {}
                  "latest" :contribution/on-emergency-changed [:district0x.log/error :on-emergency-changed]]]}})))

(reg-event-fx
  :contribution/on-contribution
  interceptors
  (fn [{:keys [db]} [{:keys [:contrib-period-index :total-contributed :contributors-count]}]]
    (let [contrib-period-index (bn/->number contrib-period-index)
          total-contributed (u/big-num->ether total-contributed)
          contributors-count (bn/->number contributors-count)]
      (when (< (get-in db [:contribution/contrib-periods contrib-period-index :contrib-period/total-contributed])
               total-contributed)
        {:db (update-in db [:contribution/contrib-periods contrib-period-index] merge
                        {:contrib-period/total-contributed total-contributed
                         :contrib-period/contributors-count contributors-count})}))))

(reg-event-fx
  :contribution/on-soft-cap-reached
  interceptors
  (fn [{:keys [db]} [{:keys [:contrib-period-index :end-time]}]]
    {:db (update-in db [:contribution/contrib-periods (bn/->number contrib-period-index)] merge
                    {:contrib-period/end-time (bn/->date-time end-time)
                     :contrib-period/soft-cap-reached? true})
     :dispatch [:district0x.snackbar/show-message "Amazing! Soft Cap was just reached!"]}))

(reg-event-fx
  :contribution/on-hard-cap-reached
  interceptors
  (fn [{:keys [db]} [{:keys [:contrib-period-index :end-time]}]]
    {:db (update-in db [:contribution/contrib-periods (bn/->number contrib-period-index)] merge
                    {:contrib-period/end-time (bn/->date-time end-time)
                     :contrib-period/hard-cap-reached? true})
     :dispatch [:district0x.snackbar/show-message "Unbelievable! Our Hard Cap was reached! The Sale is over now"]}))

(reg-event-fx
  :contribution/on-emergency-changed
  interceptors
  (fn [{:keys [db]} [{:keys [:is-stopped]}]]
    {:db (assoc db :contribution/stopped? is-stopped)
     :dispatch [:district0x.snackbar/show-message (if is-stopped
                                                    "Contribution was temporarily paused due to emergency"
                                                    "Contribution was just resumed")]}))

(reg-event-fx
  :contribution/set-contrib-period
  interceptors
  (fn [{:keys [db]} [args address-index]]
    {:dispatch [:district0x.contract/state-fn-call
                {:contract-key :contribution
                 :contract-method :set-contrib-period
                 :args (contrib-period-args args)
                 :transaction-opts {:gas 3800000
                                    :from (if address-index
                                            (nth (:my-addresses db) address-index)
                                            (:active-address db))}
                 :receipt-dispatch [:contribution/get-contrib-period constants/current-contrib-period]}]}))

(reg-event-fx
  :contribution/get-contrib-period
  interceptors
  (fn [{:keys [db]} [period-index]]
    {:db (assoc-in db [:contribution/contrib-periods period-index :contrib-period/loading?] true)
     :web3-fx.contract/constant-fns
     {:fns [[(get-instance db :contribution)
             :get-contrib-period
             period-index
             [:contribution/get-contrib-period-loaded period-index]
             [:district0x.log/error :contribution/get-contrib-period]]]}}))

(reg-event-fx
  :contribution/get-contrib-period-loaded
  interceptors
  (fn [{:keys [db]} [period-index contrib-period]]
    {:db (assoc-in db [:contribution/contrib-periods period-index]
                   (merge (parse-get-contrib-period contrib-period)
                          {:contrib-period/loading? false}))}))

(reg-event-fx
  :contribution/get-configuration
  interceptors
  (fn [{:keys [db]}]
    {:web3-fx.contract/constant-fns
     {:fns [[(get-instance db :contribution)
             :get-configuration
             [:contribution/get-configuration-loaded]
             [:district0x.log/error :contribution/get-contrib-period]]]}}))

(reg-event-fx
  :contribution/get-configuration-loaded
  interceptors
  (fn [{:keys [db]} [configuration]]
    (let [config (parse-get-configuration configuration)]
      (if-not (u/zero-address? (:contribution/wallet config))
        {:db (merge db config)}
        {:db (assoc db :contracts-not-found? true)}))))

(reg-event-fx
  :contribution/contribute
  interceptors
  (fn [{:keys [db]} [form-data address]]
    {:dispatch [:district0x.form/submit
                {:form-data form-data
                 :address address
                 :value (u/num->wei (:contribution/amount form-data))
                 :fn-key :contribution/contribute
                 :fn-args (constants/eth-contracts-fns :contribution/contribute)
                 :form-key :form.contribution/contribute
                 :receipt-dispatch [:district0x.snackbar/show-message "Thank you! Your contribution was successfully sent"]}]}))

(reg-event-fx
  :contribution/enable-contrib-period
  interceptors
  (fn [{:keys [db]} [form-data address]]
    {:dispatch [:district0x.form/submit
                {:form-data form-data
                 :address address
                 :fn-key :contribution/enable-contrib-period
                 :fn-args (constants/eth-contracts-fns :contribution/enable-contrib-period)
                 :form-key :form.contribution/enable-contrib-period
                 :receipt-dispatch-n [[:district0x.snackbar/show-message "Your agreement to enable contribution was successfully sent"]
                                      [:contribution/get-contrib-period constants/current-contrib-period]]}]}))

(reg-event-fx
  :contribution/emergency-stop
  interceptors
  (fn [{:keys [db]}]
    {:dispatch [:district0x.contract/state-fn-call {:contract-key :contribution
                                                    :contract-method :emergency-stop
                                                    :transaction-opts {:gas 200000}}]}))

(reg-event-fx
  :contribution/release
  interceptors
  (fn [{:keys [db]}]
    {:dispatch [:district0x.contract/state-fn-call {:contract-key :contribution
                                                    :contract-method :release
                                                    :transaction-opts {:gas 200000}}]}))

(reg-event-fx
  :contribution/compensate-contributors
  interceptors
  (fn [{:keys [db]} [{:keys [:contribution/period-index :offset :limit]}]]
    {:dispatch [:district0x.contract/state-fn-call {:contract-key :contribution
                                                    :contract-method :compensate-contributors
                                                    :args [period-index offset limit]
                                                    :transaction-opts {:gas 3500000}}]}))

(reg-event-fx
  :contribution/enable-district0x-network-token-transfers
  interceptors
  (fn [{:keys [db]}]
    {:dispatch [:district0x.contract/state-fn-call {:contract-key :contribution
                                                    :contract-method :enable-district0x-network-token-transfers
                                                    :args []
                                                    :transaction-opts {:gas 500000}}]}))

(reg-event-fx
  :contribution/addresses->owners?
  [interceptors]
  (fn [{:keys [db]} [addresses]]
    {:web3-fx.contract/constant-fns
     {:fns (for [address (or addresses (:my-addresses db))]
             [(get-instance db :contribution)
              :is-owner
              address
              [:contribution/address->owner?-loaded address]
              [:district0x.log/error :contribution/addresses->owners?]])}}))

(reg-event-fx
  :contribution/address->owner?-loaded
  [interceptors]
  (fn [{:keys [db]} [address owner?]]
    {:db (assoc-in db [:contribution/address->owner? address] owner?)}))

(reg-event-fx
  :watch-dnt-balances
  [interceptors]
  (fn [{:keys [db]}]
    {:dispatch [:district0x/watch-token-balances
                {:addresses (conj (:my-addresses db) (:address (get-contract db :contribution)))
                 :instance (get-instance db :dnt-token)
                 :token-code :dnt}]}))

(reg-event-fx
  :dnt-token/transfer
  [interceptors]
  (fn [{:keys [db]} [{:keys [:dnt-token/to :dnt-token/from :dnt-token/value]}]]
    {:dispatch [:district0x.contract/state-fn-call {:contract-key :dnt-token
                                                    :contract-method :transfer
                                                    :args [to value]
                                                    :transaction-opts {:gas 200000
                                                                       :from (or from (:active-address db))}}]}))

(reg-event-fx
  :reinitialize
  interceptors
  (fn [{:keys [:db]} args]
    (let [my-addresses (:my-addresses db)
          {:keys [:contribution-args :address-index]} (s/conform (s/cat :contribution-args (s/? map?)
                                                                        :address-index (s/? number?))
                                                                 args)]
      (.clear js/console)
      {:dispatch [:district0x/clear-smart-contracts]
       :async-flow {:first-dispatch [:district0x/load-smart-contracts {:version constants/contracts-version}]
                    :rules [{:when :seen?
                             :events [:district0x/smart-contracts-loaded]
                             :dispatch-n [[:deploy-all-contracts
                                           (merge
                                             (when (<= 10 (count my-addresses))
                                               {:contribution/owners (take 2 my-addresses)
                                                :contribution/required-count 1
                                                :contribution/founder1 (first my-addresses)
                                                :contribution/founder2 (second my-addresses)
                                                :contribution/early-sponsor (first my-addresses)
                                                :contribution/wallet (nth my-addresses 3)
                                                :contribution/advisers (drop 4 (take 8 my-addresses))})
                                             contribution-args)
                                           address-index]]
                             :halt? true}]}})))

(reg-event-fx
  :reinitialize+start-contribution
  interceptors
  (fn [{:keys [:db]}]
    {:dispatch [:reinitialize]
     :dispatch-later [{:ms 3000 :dispatch [:contribution/set-contrib-period
                                           {:contribution/period-index 0
                                            :contrib-period/start-time (time-coerce/to-epoch (t/plus (t/now) (t/seconds 5)))
                                            :contrib-period/end-time (time-coerce/to-epoch (t/plus (t/now) (t/hours 2)))
                                            :contrib-period/soft-cap-amount (u/eth->wei 5)
                                            :contrib-period/hard-cap-amount (u/eth->wei 100)
                                            :contrib-period/after-soft-cap-duration (t/in-seconds (t/minutes 30))}
                                           0]}
                      {:ms 4000 :dispatch [:contribution/enable-contrib-period {:contribution/period-index 0}]}]}))

(comment
  (dispatch [:contribution/set-contrib-period
             {:contribution/period-index 0
              :contrib-period/start-time (time-coerce/to-epoch (t/plus (t/now) (t/hours 1)))
              :contrib-period/end-time (time-coerce/to-epoch (t/plus (t/now) (t/hours 2)))
              :contrib-period/soft-cap-amount (u/eth->wei 55000)
              :contrib-period/after-soft-cap-duration (t/in-seconds (t/minutes 30))
              :contrib-period/hard-cap-amount (u/eth->wei 65000)}
             0])

  (dispatch [:contribution/set-contrib-period
             {:contribution/period-index 0
              :contrib-period/start-time (time-coerce/to-epoch (t/plus (t/now) (t/seconds 30)))
              :contrib-period/end-time (time-coerce/to-epoch (t/plus (t/now) (t/hours 2)))
              :contrib-period/soft-cap-amount (u/eth->wei 5)
              :contrib-period/after-soft-cap-duration (t/in-seconds (t/minutes 10))
              :contrib-period/hard-cap-amount (u/eth->wei 10)}
             0])

  (dispatch [:contribution/set-contrib-period
             {:contribution/period-index 0
              :contrib-period/start-time (cljs-time.coerce/to-epoch (cljs-time.core/date-time 2017 7 18 15))
              :contrib-period/end-time (cljs-time.coerce/to-epoch (t/plus (cljs-time.core/date-time 2017 7 18 15)
                                                                          (t/weeks 2)))
              :contrib-period/soft-cap-amount (u/eth->wei 38400)
              :contrib-period/after-soft-cap-duration (t/in-seconds (t/days 2))
              :contrib-period/hard-cap-amount (u/eth->wei 191900)}])

  (dispatch [:district0x.contract/constant-fn-call :contribution :district0x-network-token])
  (dispatch [:district0x.contract/constant-fn-call :contribution :founder1])
  (dispatch [:district0x.contract/constant-fn-call :dnt-token :token-grants-count (:contribution/founder1 @re-frame.db/app-db)])
  (dispatch [:district0x.contract/constant-fn-call :dnt-token :token-grant (:contribution/founder1 @re-frame.db/app-db) 1])
  (dispatch [:district0x.contract/constant-fn-call :contribution :get-running-contrib-period])
  (dispatch [:district0x.contract/constant-fn-call :contribution :get-times])
  (dispatch [:contribution/enable-contrib-period
             {:contribution/period-index 0}
             (last (:my-addresses @re-frame.db/app-db))])
  )