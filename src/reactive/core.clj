(ns reactive.core)


(defprotocol IObservable
  (attach [this k sink] "Attaches sink to this event stream with the specified key")
  (detatch [this k] "Detatches the sink associated with k")
  (deref-value [this] "Gets the current value of this value"))

(defprotocol IObserver
  (get-socket [this] [this nm] "Gets a socket with the given name, defaults to :default"))

(defprotocol IEventSocket
  (mark-dirty [this f] "Marks this socket as dirty, instructing it to clear its cache and call f when it needs to
                        update its cache")
  (push-update [this]))


; This represents a contstant that has been lifted

(deftype LiftedValue [v]
  IObservable
  (attach [this k sink]
          (mark-dirty sink #(identity v))
          (push-update sink))
  (detatch [this k] nil)
  (deref-value [this] v))

(defn lift [v]
  (if-not (extends? IObservable (type v))
    (LiftedValue. v)
    v))

(defn unknown? [v]
  (= v ::unknown))

(defn get-vals [state]
  (map :value-fn (vals (:slots @state))))

(defn should-mark-dirty? [state]
  (let [vals (get-vals state)]
    (println vals @state (:mark-dirty? @state))
    (apply (:mark-dirty? @state) vals)))

(defn get-deref-value [state]
  (let [vals (get-vals state)
        update? (apply (:push-update? @state) vals)]
    (if update?
      (apply (:map-fn @state) vals)
      ::unknown)))



(defn reactive-node [slots mark-dirty? push-update? map-fn]
  (let [state {:slots (into {}
                            (map (fn [s]
                                   [s {:value-fn ::unknown
                                       :is-dirty? true}])
                                 slots))
               :mark-dirty? mark-dirty?
               :push-update? push-update?
               :map-fn map-fn
               :value ::unknown
               :listeners {}}
        state (atom state)]
    (reify
      IObservable
      (attach [this k sink]
        (swap! state assoc-in [:listeners k] sink)
        (mark-dirty sink #(deref-value this)))
      (detatch [this k]
        (swap! state update-in [:listeners] #(dissoc k)))
      (deref-value [this]
        (if (unknown? (:value @state))
          (get-deref-value state)
          (:value @state)))

      clojure.lang.IDeref
      (deref [this]
        (deref-value this))

      IObserver
      (get-socket [this] (get-socket this (first slots)))
      (get-socket [this s]
        (reify
          IEventSocket
          (mark-dirty [sthis value-fn]
            (swap! state assoc-in [:slots s :value-fn] value-fn)
            (when (should-mark-dirty? state)
              (swap! state assoc :value ::unknown)
              (doall (for [[_ v] (:listeners @state)]
                       (mark-dirty v #(deref-value this))))))

          (push-update [sthis]
            (when (apply (:push-update? @state) (get-vals state))
              (doall (for [[_ v] (:listeners @state)]
                       (push-update v)))))
          ))
      )


    ))


(defn reactive-atom []
  (reactive-node
   [:default]
   (fn [default] true)
   (fn [default] true)
   (fn [default]
     (if (unknown? default)
       default
       (default)))))





(defn connect
   ([src dest]
              (connect src dest :default))
           ( [src dest dk]
               (let [dests (get-socket dest dk)]
                 (attach src dest dests))))