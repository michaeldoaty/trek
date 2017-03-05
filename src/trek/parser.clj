(ns trek.parser
  (:require [clojure.spec :as s]))

(defn parse-query
  "Parses a query into a vector of vectors consisting of a dispatch
  value and the attributes for that dispatch."
  [query]
  (let [root-key   (apply key query)
        root-value (apply val query)]

    (loop [current-path     [root-key]
           current-value    root-value
           not-parsed-queue [[current-path root-value]]
           result           []
           attrs            []]

      (cond

        (keyword? (first current-value))
        (recur current-path
               (vec (rest current-value))
               not-parsed-queue
               result
               (conj attrs (first current-value)))

        (map? (first current-value))
        (let [m    (first current-value)
              k    (apply key m)
              path (conj current-path k)
              v    (apply val m)]
          (recur current-path
                 (vec (rest current-value))
                 (conj not-parsed-queue [path v])
                 result
                 attrs))

        (empty? not-parsed-queue)
        result

        (nil? (first current-value))
        (let [new-parsed-queue (vec (rest not-parsed-queue))
              [new-path new-value] (first new-parsed-queue)]
          (recur new-path
                 new-value
                 new-parsed-queue
                 (conj result [current-path attrs])
                 []))))))


(defn entity-attrs
  "Takes a entity spec and parses its attributes/keyword specs"
  [spec]
  (loop [spec  spec
         attrs []]

    (let [item (first spec)]

      (cond
        (nil? item)
        attrs

        (and (keyword? item)
             (not-any? #{:req :req-un :opt :opt-un} [item]))
        (recur
          (rest spec)
          (conj attrs item))

        (sequential? item)
        (recur
          (concat (rest spec) item)
          attrs)

        :else
        (recur
          (rest spec)
          attrs)))))


(defn normalize-dispatch
  "Normalizes the path to return an entity type plus an attribute.
  For example, [:user :friends :applications] turns into [:user :applications]
  if :friends is a type of :user"

  [path link-map]
  (letfn [(get-entity [v k]
            (let [entity (get link-map (conj v k))]
              [entity]))]

    (conj
      (reduce get-entity [] (drop-last path))
      (last path))))


(defn link-map
  "Takes an entity and a map of links and creates links to entity and entity to entity mappings.
  For example, :user and {:friends :user} returns {:user :user, [:user :friends] :user}"
  [entity m]
  (letfn [(create-link-map [link-map [link-k link-v]]
            (assoc link-map [entity link-k] link-v))]

    (-> create-link-map
        (reduce {} (:links m))
        (assoc [entity] entity))))


(defn expand-entity-map
  "Expands entity map by resolving spec to attributes and creating a link map"
  [entity-map]
  (letfn [(expand-map [entity-map [entity m]]
            (let [attrs (if (:spec m)
                          (entity-attrs (s/form (:spec m)))
                          [])]
              (-> entity-map
                  (assoc-in [:entity-map entity :attrs] attrs)
                  (update-in [:link-map] merge (link-map entity m)))))]

    (reduce expand-map {:entity-map entity-map :link-map {}} entity-map)))


(defn resolve-query
  "..."
  [m query]
  (let [{:keys [link-map entity-map]} m
        [dispatch-value dispatch-attrs] query]
    (if (empty? dispatch-attrs)
      m
      (let [{:keys [link-map entity-map]} m
            [dispatch-value dispatch-attrs] query
            entity          (get link-map dispatch-value)
            resolver        (get-in entity-map [entity :resolver])
            last-result     (flatten [:result (drop-last dispatch-value)])
            previous-result (get-in m last-result ::not-found)
            previous-seq?   (sequential? previous-result)
            seq-previous    (if (or (= previous-result ::not-found) previous-seq?)
                              previous-result
                              [previous-result])
            resolved        (resolver dispatch-value previous-result nil)
            final-resolved  (if (and (not= previous-result ::not-found) (not previous-seq?))
                              (first resolved)
                              resolved)]
        (assoc-in m (flatten [:result dispatch-value]) final-resolved)))))


(defn execute-query
  "..."
  [query entity-map]
  (let [parsed-query        (parse-query query)
        expanded-entity-map (expand-entity-map entity-map)]
    (:result (reduce resolve-query expanded-entity-map parsed-query))))
