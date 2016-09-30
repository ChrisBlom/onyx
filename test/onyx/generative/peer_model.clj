(ns onyx.generative.peer-model
  (:require [clojure.core.async :as a :refer [>!! <!! alts!! promise-chan close! chan poll!]]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :refer [info error warn fatal]]
            [onyx.log.entry :refer [create-log-entry]]
            [onyx.static.logging-configuration :as logging-config]
            [onyx.test-helper :refer [load-config with-test-env playback-log]]
            [onyx.peer.task-lifecycle :as tl]
            [onyx.peer.communicator]
            [onyx.log.zookeeper]
            [onyx.mocked.zookeeper]
            [onyx.log.failure-detector]
            [onyx.log.commands.common :as common]
            [onyx.mocked.failure-detector]
            [onyx.mocked.log]
            [onyx.peer.coordinator :as coord]
            [onyx.log.replica]
            [onyx.extensions :as extensions]
            [onyx.system :as system]
            [onyx.messaging.aeron :as aeron]
            [com.stuartsierra.component :as component]
            [onyx.static.uuid :refer [random-uuid]]
            [onyx.messaging.messenger :as m]
            [onyx.messaging.immutable-messenger :as im]
            [onyx.peer.peer-group-manager :as pm]
            [clojure.test.check.generators :as gen]))

(def n-max-groups 10)
(def n-max-peers 1)
(def max-iterations 1)

(def peer-group-num-gen
  (gen/fmap (fn [oid]
              (keyword (str "g" oid)))
            (gen/resize (dec n-max-groups) gen/pos-int)))

(def peer-num-gen
  (gen/fmap (fn [oid]
              (keyword (str "p" oid)))
            (gen/resize (dec n-max-peers) gen/pos-int)))

(def add-peer-group-gen
  (gen/fmap (fn [g] 
              {:type :orchestration
               :command :add-peer-group
               :group-id g})
            peer-group-num-gen))

(def remove-peer-group-gen
  (gen/fmap (fn [g] 
              [;; Need to manually emit the stop peer group as 
               ;; our peer group manager component would normally
               ;; emit a command on being stopped
               {:type :group
                :command :write-group-command
                :args [:stop-peer-group]
                :group-id g}

               {:type :group
                :command :play-group-commands
                :group-id g
                :iterations 10}

               ;; Finally stop the peer group via the api and remove from our model
               {:type :orchestration
                :command :remove-peer-group
                :group-id g}])
            peer-group-num-gen))

(def add-peer-gen
  (gen/fmap (fn [[g p]] 
              {:type :group
               :command :write-group-command
               :group-id g
               :args [:add-peer [g p]]}) 
            (gen/tuple peer-group-num-gen 
                       peer-num-gen)))

(def remove-peer-gen
  (gen/fmap (fn [[g p]]
              {:type :group
               :command :write-group-command
               :args [:remove-peer [g p]]
               :group-id g}) 
            (gen/tuple peer-group-num-gen
                       peer-num-gen)))

;; Does everything necessary so that the peer will actually leave the group
(def full-remove-peer-gen
  (gen/fmap (fn [[g p]]
              [{:type :group
                :command :write-group-command
                :args [:remove-peer [g p]]
                :group-id g}
               {:type :group 
                :command :write-outbox-entries
                :group-id g
                :iterations 10}
               {:type :group
                :command :apply-log-entries
                :group-id g
                :iterations 100}]) 
            (gen/tuple peer-group-num-gen
                       peer-num-gen)))

(def write-outbox-entries-gen
  (gen/fmap (fn [[g n]]
              {:type :group 
               :command :write-outbox-entries
               :group-id g
               :iterations n})
            (gen/tuple peer-group-num-gen
                       (gen/resize (dec max-iterations) gen/s-pos-int))))

(def play-group-commands-gen
  (gen/fmap (fn [[g n]] 
              {:type :group
               :command :play-group-commands
               :group-id g
               :iterations n})
            (gen/tuple peer-group-num-gen
                       (gen/resize (dec max-iterations) gen/s-pos-int))))

(def apply-log-entries-gen
  (gen/fmap (fn [[g n]] 
              {:type :group
               :command :apply-log-entries
               :group-id g
               :iterations n})
            (gen/tuple peer-group-num-gen
                       (gen/resize (dec max-iterations) gen/s-pos-int))))

(def restart-peer-group-gen 
  (gen/fmap (fn [g] 
              {:type :group
               :command :write-group-command
               :args [:restart-peer-group]
               :group-id g})
            peer-group-num-gen))

(def task-iteration-gen 
  (gen/fmap (fn [[g p]] 
              {:type :peer
               :command :task-iteration
               ;; Should be one for each known peer in the group, once it's
               ;; not one peer per group
               :group-id g
               :peer-owner-id [g p]
               :iterations 1})
            (gen/tuple peer-group-num-gen
                       peer-num-gen)))

(def periodic-barrier 
  (gen/fmap (fn [[g p]] 
              {:type :peer
               :command :periodic-barrier
               ;; Should be one for each known peer in the group, once it's
               ;; not one peer per group
               :group-id g
               :peer-owner-id [g p]
               :iterations 1})
            (gen/tuple peer-group-num-gen
                       peer-num-gen)))

(def offer-barriers 
  (gen/fmap (fn [[g p]] 
              {:type :peer
               :command :offer-barriers
               ;; Should be one for each known peer in the group, once it's
               ;; not one peer per group
               :group-id g
               :peer-owner-id [g p]
               :iterations 1})
            (gen/tuple peer-group-num-gen
                       peer-num-gen)))

(defn write-outbox-entries [state entries]
  (reduce (fn [s entry]
            (update-in s 
                       [:comm :log]
                       (fn [log]
                         (if log
                           (extensions/write-log-entry log entry))))) 
          state
          entries))

(defn drain-outbox! [log outbox-ch _]
  (assert log)
  (assert outbox-ch)
  (loop []
    (when-let [entry (poll! outbox-ch)]
      (extensions/write-log-entry log entry)
      (recur))))

(defn apply-log-entries [state n]
  (reduce (fn [s _]
            (if (and (not (:stopped? s))
                     (:connected? s))
              (let [log (get-in s [:comm :log])] 
                (if-let [entry (extensions/read-log-entry log (:entry-num log))]
                  (-> s
                      (update-in [:comm :log :entry-num] inc)
                      (pm/action [:apply-log-entry entry])) 
                  ;; Can short circuit as there are no entries
                  (reduced s)))
              s)) 
          state
          (range n)))

(defn play-group-commands [state n]
  (reduce pm/action
          state
          (keep (fn [_] (poll! (:group-ch state)))
                (range n))))

(defn apply-group-command [groups {:keys [command group-id] :as event}]
  ;; Default case is that this will be a group command
  (if-let [group (get groups group-id)]
    (assoc groups 
           group-id 
           (update group 
                   :state
                   (fn [state]
                     (case command 
                       :write-outbox-entries
                       (write-outbox-entries state 
                                             (keep (fn [_] 
                                                     (when-let [ch (:outbox-ch state)]
                                                       (poll! ch)))
                                                   (range (:iterations event))))

                       :apply-log-entries
                       (apply-log-entries state (:iterations event))

                       :play-group-commands
                       (play-group-commands state (:iterations event))

                       :write-group-command
                       (do 
                        (println (.buf (:group-ch state)))
                        (when (> (count (.buf (:group-ch state))) 99999)
                          (throw (Exception. (str "Too much for buffer " (count (.buf (:group-ch state)))))))
                        (>!! (:group-ch state) (:args event))
                        state)))))
    groups))

(defn drain-commands 
  "Repeatedly plays a stanza of commands that will ensure all operations are complete"
  [groups]
  (let [commands (mapcat 
                  (fn [g] 
                    [{:type :group
                      :command :play-group-commands
                      :group-id g
                      :iterations 10}
                     {:type :group 
                      :command :write-outbox-entries
                      :group-id g
                      :iterations 10}
                     {:type :group
                      :command :apply-log-entries
                      :group-id g
                      :iterations 10}])
                  (keys groups))
        new-groups (reduce apply-group-command groups commands)]
    (if (= groups new-groups)
      ;; Drained 
      new-groups
      (recur new-groups))))

(defn group-id->port [gid]
  (+ 40000 (Integer/parseInt (apply str (rest (name gid))))))

(defn new-group [peer-config group-id]
  (println "peer-config" peer-config group-id)
  {:peer-state {}
   :state (-> (onyx.api/start-peer-group (assoc peer-config :onyx.messaging/peer-port (group-id->port group-id)))
              :peer-group-manager 
              :initial-state 
              (pm/action [:start-peer-group]))})

(defn get-peer-id [group peer-owner-id]
  (get-in group [:state :peer-owners peer-owner-id]))

(defn task-component-path [peer-id] 
  [:state :vpeers peer-id :virtual-peer :state :started-task-ch])

(defn assert-correct-replicas [written new-state]
  (assert (or (empty? written) 
              (= #{(m/replica-version (:messenger new-state))}
                 (set (map :replica written))))
          "something was written but replica versions weren't right"))

(defn conj-written-segments [batches command prev-state new-state prev-replica-version]
  (let [written (if (= command :task-iteration) 
                  (seq (:null/not-written (:event new-state))))]  
    (assert-correct-replicas written new-state)

    (cond-> (vec batches)

      (not= prev-replica-version
            (m/replica-version (:messenger new-state)))
      (conj [:reset-messenger])

      written 
      (conj written))))

(defn next-coordinator-state [coordinator command]
  (let [state (:coordinator-thread coordinator)]
    (if (coord/started? coordinator)
      (assoc coordinator 
             :coordinator-thread 
             (onyx.peer.coordinator/coordinator-action command state (:prev-replica state)))
      coordinator)))

(defn next-state [prev-state command replica]
  (cond (= command :task-iteration) 
        (tl/next-state prev-state replica)

        (#{:periodic-barrier :offer-barriers} command)
        (update prev-state :coordinator next-coordinator-state command)))

(defn apply-peer-commands [groups {:keys [command group-id peer-owner-id]}]
  (let [group (get groups group-id)
        peer-id (get-peer-id group peer-owner-id)]
    (if peer-id
      (let [task-component (get-in group (task-component-path peer-id))] 
        ;; If we can access the event, it means the peer has started its task lifecycle
        (if task-component
          (let [init-state (get-in @task-component [:task-lifecycle :state])
                current-replica (:replica (:state group))
                new-allocation (common/peer->allocated-job (:allocations current-replica) peer-id)
                prev-state (or (get-in @task-component [:task-lifecycle :prev-state])
                               init-state)
                ;; capture replica version here as it is mutable inside the messenger
                prev-replica-version (m/replica-version (:messenger prev-state))
                new-state (next-state prev-state command current-replica)]
            ;; Assoc into task state to provide a way to shutdown state from task component
            (when-let [state-container (:holder (:task-lifecycle @task-component))]
              (reset! state-container new-state))
            ;; Maintain state ourselves, over the task rather than the component
            (swap! task-component assoc-in [:task-lifecycle :prev-state] new-state)
            (update-in groups
                       [group-id :peer-state peer-id (:task new-allocation) :written] 
                       (fn [w] (conj-written-segments w command prev-state new-state prev-replica-version))))
          groups))
      groups)))

(defn apply-orchestration-command [groups peer-config {:keys [command group-id]}]
  (println "Applying " command group-id)
  (case command
    :remove-peer-group
    (do (when-let [group (get groups group-id)]
          (pm/action (:state group) [:stop-peer-group]))
        (dissoc groups group-id))

    :add-peer-group
    (update groups 
            group-id 
            (fn [group]
              (if group
                group
                (new-group peer-config group-id))))))

(defn apply-event [peer-config groups event]
  (try
   (if (vector? event)
     (reduce #(apply-event peer-config %1 %2) groups event)
     (case (:type event)

       :drain-commands
       (drain-commands groups)

       :orchestration
       (apply-orchestration-command groups peer-config event)

       :peer
       (apply-peer-commands groups event)

       :event
       (case (:command event)
         :submit-job (do ;; Quite stateful
                         (onyx.api/submit-job peer-config (:job (:job-spec event)))
                         groups))

       :group
       (apply-group-command groups event)

       (throw (Exception. (str "Unhandled command " (:type event))))))
   (catch Throwable t
     (throw (ex-info "Unhandled exception" {:groups groups} t)))))

(defn apply-model-command [model event]
  (if (sequential? event)
    (reduce apply-model-command model event)
    (let [{:keys [command type group-id]} event] 
      (case command
        :add-peer-group 
        (update model :groups conj group-id)
        :remove-peer-group
        (-> model 
            (update :groups disj group-id)
            (update :peers dissoc group-id))
        :write-group-command 
        (if (get (:groups model) group-id)
          (let [[grp-cmd & args] (:args event)] 
            (case grp-cmd
              :add-peer
              (update model :peers update group-id (fn [peers] (conj (set peers) (first args))))
              :remove-peer
              (update model :peers update group-id (fn [peers] (disj (set peers) (first args))))
              model))
          model)
        model))))

(defn model-commands [commands]
  (reduce apply-model-command {:groups #{} :peers {}} commands))

(defrecord SharedAtomMessagingPeerGroup [immutable-messenger opts]
  m/MessengerGroup
  (peer-site [messenger peer-id]
    {})

  component/Lifecycle
  (start [component]
    component)

  (stop [component]
    component))

(defn play-events [events uuid-seed messenger-type]
  (let [zookeeper-log (atom nil)
        zookeeper-store (atom nil)
        checkpoints (atom nil)
        random-gen (atom nil)
        shared-immutable-messenger (atom nil)
        ;; Share a messaging peer group even if we use separate groups
        shared-peer-group (fn [peer-config]
                            (->SharedAtomMessagingPeerGroup shared-immutable-messenger peer-config))]
    (with-redefs [;; Group overrides
                  onyx.log.zookeeper/zookeeper (partial onyx.mocked.zookeeper/fake-zookeeper zookeeper-log zookeeper-store checkpoints) 
                  onyx.peer.communicator/outbox-loop (fn [_ _ _])
                  onyx.peer.communicator/close-outbox! drain-outbox!
                  onyx.log.failure-detector/failure-detector onyx.mocked.failure-detector/failure-detector
                  ;; Make peer group linearizable by dropping the thread / loop
                  pm/peer-group-manager-loop (fn [state])
                  onyx.static.uuid/random-uuid (fn [] 
                                                 (java.util.UUID. (.nextLong @random-gen)
                                                                  (.nextLong @random-gen)))
                  onyx.peer.coordinator/start-coordinator! (fn [state] state)
                  onyx.peer.coordinator/stop-coordinator! (fn [coordinator] 
                                                            (let [state (:coordinator-thread coordinator)] 
                                                              (when (coord/started? coordinator)
                                                                (onyx.peer.coordinator/coordinator-action :shutdown state (:prev-replica state)))))
                  onyx.peer.coordinator/next-replica (fn [coordinator replica]
                                                       (if (coord/started? coordinator)
                                                         ;; store all our state in the coordinator thread key 
                                                         ;; since we've overridden start coordinator
                                                         (let [state (:coordinator-thread coordinator)] 
                                                           (assoc coordinator
                                                                  :coordinator-thread 
                                                                  (onyx.peer.coordinator/coordinator-action 
                                                                   :reallocation (:coordinator-thread coordinator) replica)))
                                                         coordinator))
                  ;; Make start and stop threadless / linearizable
                  ;; Try to get rid of the component atom here
                  onyx.messaging.messenger/build-messenger-group (case messenger-type
                                                                   :aeron onyx.messaging.messenger/build-messenger-group 
                                                                   :atom shared-peer-group)
                  onyx.log.commands.common/start-task! (fn [lifecycle]
                                                         (atom (component/start lifecycle)))
                  onyx.log.commands.common/build-stop-task-fn (fn [_ component]
                                                                (fn [scheduler-event] 
                                                                  (component/stop 
                                                                   (assoc-in @component 
                                                                             [:task-lifecycle :scheduler-event] 
                                                                             scheduler-event))))
                  ;; Task overrides
                  tl/final-state (fn [component] 
                                   @(:holder component))
                  tl/start-task-lifecycle! (fn [_ _] (a/thread :immediate-exit))]
      (let [_ (reset! zookeeper-log [])
            _ (reset! zookeeper-store {})
            _ (reset! checkpoints {})
            _ (reset! shared-immutable-messenger (im/immutable-messenger {}))
            _ (reset! random-gen (java.util.Random. uuid-seed))
            onyx-id (random-uuid)
            config (load-config)
            env-config (assoc (:env-config config) 
                              :onyx/tenancy-id onyx-id
                              :onyx.log/config {:level :error})
            peer-config (assoc (:peer-config config) 
                               :onyx.peer/outbox-capacity 1000000
                               :onyx.peer/inbox-capacity 1000000
                               ;; Don't sleep, ever
                               :onyx.peer/drained-back-off 0
                               :onyx.peer/peer-not-ready-back-off 0
                               :onyx.peer/job-not-ready-back-off 0
                               :onyx.peer/join-failure-back-off 0
                               :onyx.peer/state-log-impl :mocked-log
                               :onyx.messaging.aeron/embedded-driver? false
                               :onyx/tenancy-id onyx-id
                               :onyx.messaging/impl messenger-type
                               :onyx.log/config {:level :error})
            groups {}
            embedded-media-driver (component/start (aeron/->EmbeddedMediaDriver 
                                                    (assoc peer-config 
                                                           :onyx.messaging.aeron/embedded-driver? (= messenger-type :aeron))))]
        (try
         (let [final-groups (reduce #(apply-event peer-config %1 %2) groups events)
               ;_ (println "Final " @zookeeper-log)
               _ (println "Number log entries:" (count @zookeeper-log))
               ;; FIXME, shouldn't have to hack version in everywhere
               final-replica (reduce #(extensions/apply-log-entry %2 (assoc %1 :version (:message-id %2))) 
                                     (onyx.log.replica/starting-replica peer-config)
                                     @zookeeper-log)]
           (run! (fn [[group-id group]] 
                   (try 
                    (pm/action (:state group) [:stop-peer-group])
                    (catch Throwable t
                      (println t))))
                 final-groups)

           ;(println "final log " @zookeeper-log)
            {:replica final-replica 
             :groups final-groups})
         (catch Throwable t
           {:exception (.getCause t)
            :groups (:groups (ex-data t))})
         (finally
          (component/stop embedded-media-driver)))))))

;; Job generator code
; (def gen-task-name (gen/fmap #(keyword (str "t" %)) gen/s-pos-int))

; (defn task->type [graph task]
;   (cond (empty? (dep/immediate-dependents graph task))
;         :output
;         (empty? (dep/immediate-dependencies graph task))
;         :input
;         :else
;         :function))

; (defn to-dependency-graph-safe [workflow]
;   (reduce (fn [[g wf] edge]
;             (try 
;               [(apply dep/depend g (reverse edge))
;                (conj wf edge)]
;               (catch Throwable t
;                 [g wf])))
;           [(dep/graph) []] 
;           workflow))

; (def build-workflow-gen
;   (gen/fmap (fn [workflow] 
;               (let [[g wf] (to-dependency-graph-safe workflow)]
;                 {:workflow wf
;                  :task->type (->> wf 
;                                   (reduce into [])
;                                   (map (fn [t] [t (task->type g t)])))})) 
;             (gen/such-that (complement empty?) 
;                            (gen/vector (gen/such-that #(not= (first %) (second %)) 
;                                                       (gen/tuple gen-task-name gen-task-name))))))


