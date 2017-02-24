(ns trek.examples
  (:require [clojure.spec :as s]
            [clojure.core.async :as async]
            [clojure.pprint :as pp]
            [trek.parser :as parser])
  (:import (clojure.lang MultiFn)))

(defn ranged-rand
  "Returns random int in range start <= rand < end"
  [start end]
  (+ start (long (rand (- end start)))))

(s/fdef ranged-rand
        :args (s/and (s/cat :start int? :end int?)
                     #(< (:start %) (:end %)))
        :ret int?
        :fn (s/and #(>= (:ret %) (-> % :args :start))
                   #(< (:ret %) (-> % :args :end))))

(s/form (:ret (s/get-spec `ranged-rand)))

(def query {:user [:first-name
                   :last-name
                   {:posts [:author
                            :created_at]}]})

(declare all-users user-posts find-user authenticated context users get-user-posts me)

;;; user multimethod

(defmulti user (fn [path _ _] path))

(defmethod user :first-name [_ user context]
  (:first-name user))

(defmethod user :last-name [_ user context]
  (:last-name user))

(defmethod user :posts [_ user context]
  (get-user-posts user (-> context :state :db)))


(declare create-user send-user-to-kafka)

;; mutation map
;; function spec required to have args and ret
{:user/create-user   {:trek/state [:db]
                      :trek/fn    create-user}

 :user/send-to-kafka {:trek/state [:kafka]
                      :trek/fn    send-user-to-kafka}}


;;; mutation implementation
;;; on implementation, you must provide an id in the args if you use a query
[{:user/create-user {:args  {:user/id             5
                             :user/username       "joe"
                             :user/favorite-color "blue"}

                     :query {:user [:user/username
                                    :user/favorite-color]}}}]


;;; post multimethod

(defmulti post (fn [path _ _] path))

(defmethod post :author [path _ _]
  (:author post))

(defmethod post :created-at [path _ _]
  (:created-at post))


;;; There are three ...
;;; entities
;;; queries
;;; mutations

{:users {:trek/state [:db]
         :trek/fn    users}}

{:users [{:user [::first-name
                 ::last-name]}]}

{:users [{:id         ""
          :first-name ""
          :last-name  ""}]}

{:me {:trek/state [:db]
      :trek/desc  "Get the current user"
      :trek/fn    me}}

{:me [:first-name
      :last-name]}

(defn spec? [spec]
  (try
    (not= (s/form spec) :clojure.spec/unknown)
    (catch Exception _ false)))

(s/def ::title string?)
(s/def ::author string?)
(s/def ::post (s/keys :req [::title ::author]))

(s/def :user/first-name string?)
(s/def :user/last-name string?)
(s/def :user/posts (s/coll-of string?))
(s/def :user/id number?)
(s/def ::user (s/keys :req [:user/first-name :user/last-name]))

(s/def :post/id number?)

(s/def :state/db string?)

(s/def :trek/keyword-spec (s/and qualified-keyword? spec?))
(s/def :trek/id :trek/keyword-spec)
(s/def :trek/spec :trek/keyword-spec)
(s/def :trek/links (s/map-of :trek/keyword-spec keyword?))
(s/def :trek/state (s/coll-of :trek/keyword-spec))
(s/def :trek/desc string?)
(s/def :trek/resolver #(instance? MultiFn %))
(s/def :trek/attrs (s/coll-of :trek/keyword-spec))


(s/def :trek/entity-config (s/keys :req [:trek/id :trek/spec :trek/state :trek/resolver]
                                   :opt [:trek/desc :trek/attrs :trek/linkskk]))

(s/def :trek/entity-map (s/map-of keyword? :trek/entity-config))

(def entity-map {:entity/user {:trek/id       :user/id
                               :trek/spec     ::user
                               :trek/links    {:user/posts :entity/post}
                               :trek/state    [:state/db]
                               :trek/desc     "The user of the system"
                               :trek/resolver user}

                 :entity/post {:trek/id       :post/id
                               :trek/spec     ::post
                               :trek/state    [:state/db]
                               :trek/desc     "The post of the system"
                               :trek/resolver post}})

(def links-map {:user  :user
                :posts :post
                :post  :post})

(defn create-result-entry [result path]
  (assoc-in result [path] []))

;;; query
(def query {:me [:first-name
                 :last-name
                 {:friends [:first-name
                            :last-name]}
                 {:posts [:title
                          :created-at
                          {:author [:first-name
                                    :last-name]}]}]})


(defn normalize-dispatch
  "Normalizes the path to return an entity type plus an attribute.
  For example, [:user :friends :applications] turns into [:user :applications]
  if :friends is a type of :user"

  [path links-map]
  (letfn [(get-entity [v k]
            (let [entity (get links-map (conj v k))]
              [entity]))]

    (conj
      (reduce get-entity [] (drop-last path))
      (last path))))


(defn parser [query entity]
  (let [root-key   (or entity (apply key query))
        root-value (apply val query)]

    (loop [current-path   [root-key]
           current-value  root-value
           unparsed-query []
           result         {current-path []}]

      (cond
        (keyword? (first current-value))
        (recur
          current-path
          (vec (rest current-value))
          unparsed-query
          (update-in result [current-path] conj (first current-value)))

        (map? (first current-value))
        (let [new-key   (apply key (first current-value))
              new-value (apply val (first current-value))
              new-path  (conj current-path new-key)]
          (recur
            current-path
            (vec (rest current-value))
            (conj unparsed-query [new-path new-value])
            (-> result
                (assoc-in [new-path] [])
                (update-in [current-path] conj new-key))))

        (and (not (empty? unparsed-query))
             (empty? current-value))
        (let [[new-path new-value] (first unparsed-query)]
          (recur
            new-path
            new-value
            (vec (rest unparsed-query))
            result))

        :else
        result))))


(def parsed-query
  {[:user]                [:first-name :last-name :friends :posts]
   [:user :friends]       [:first-name :last-name]
   [:user :posts]         [:title :created-at :author]
   [:user :posts :author] [:first-name :last-name]})

;{:attr :first-name
; :path [:user]
; :resolver-data {}}
(defn resolver-data [data]
  nil)

;;; take root path key
;;; pass path key and attr value to pipeline channel
;;; once value comes back from the pipeline check for link -- alt -- check if a link, if so add to path to todo-list
;;; -- done when channel is closed

;(let [to-chan       (async/chan)
;      from-chan     (async/chan)
;      parsed-data   parsed-query
;      resolved-data {}
;      path          [:user]]
;
;  (async/pipeline-blocking 5 to-chan (map resolver-data) from-chan)
;
;  (doseq [attr (get parsed-query path)]
;    (async/put! from-chan {:attr          attr
;                           :path          path
;                           :resolved-data resolved-data}))
;
;  (async/go-loop [v (async/<! from-chan)
;                  result {}]
;    (if-not v
;      result
;      (recur
;        (async/<! from-chan)
;        result))))


;(defn get-attrs [data context]
;  (let [parsed-query (:parsed-query context)
;        entity-map   (:entity-map context)
;        entity       (:entity context)
;        links-map    (:links-map context)]
;
;    (loop [attrs    (get parsed-query [entity])
;           data     data
;           result   {}
;           resolver (-> entity-map entity :trek/resolver)]
;
;      (cond
;        (empty? attrs)
;        result
;
;        (let [dispatch-key (first attrs)
;              new-data     (resolver dispatch-key data context)
;              link         (get-in links-map [entity (first attrs)])]
;          ;; resolve
;          ;; check if coll, if so map
;          ;; check if a link, if so get children
;          nil)
;
;        :else
;        nil))))
;
;(defn execute [mutation-key mutations context]
;  (let [f      (:trek/fn (get mutations mutation-key))
;        result (f (:args context))]
;    (if (coll? result)
;      (map #(get-attrs % context))
;      (get-attrs result context))))

;(parser query :user)


(s/fdef links
        :args (s/cat :entity-map :trek/entity-map
                     :entity-pair (s/cat :entity-key keyword?
                                         :entity-config :trek/entity-config))
        :ret nil)

(defn- links
  "Reduce function for entity map expansion.  Merges trek/links to a trek/link-map key on entity map."
  [entity-map [entity-key entity-config]]
  (update-in entity-map [:trek/link-map] merge (:trek/links entity-config) {entity-key entity-key}))


(s/fdef expand-entity-map
        :args (s/cat :entity-map :trek/entity-map)
        :ret (s/keys :req [:trek/entity-map :trek/link-map]))

(defn expand-entity-map
  "Expands entity-map by adding link map and expanding spec keys"
  [entity-map]
  (letfn [(expand-entity-map [m [k v :as k-v]]
            (-> m
                (links k-v)
                (assoc-in [k :trek/attrs] (parser/entity-attrs (s/form (:trek/spec v))))))]
    (reduce expand-entity-map entity-map entity-map)))

;(pp/pprint (expand-entity-map entity-map))

(s/conform :trek/entity-map entity-map)
