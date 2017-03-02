(ns trek.examples
  (:require [clojure.spec :as s]
            [clojure.core.async :as async]
            [clojure.pprint :as pp]
            [clojure.test.check.generators :as gen]
            [clojure.string :as str]
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


(declare all-users user-posts find-user authenticated context users get-user-posts me)

;;; user multimethod

(defmulti user (fn [path _ _] path))

(defmethod user :user/first-name [_ user context]
  (:first-name user))

(defmethod user :user/last-name [_ user context]
  (:last-name user))

(defmethod user :user/posts [_ user context]
  (get-user-posts user (-> context :state :db)))


(declare create-user send-user-to-kafka)

;; mutation map
;; function spec required to have args and ret
{:mutation/create-user   {:trek/state [:db]
                          :trek/fn    create-user}

 :mutation/send-to-kafka {:trek/state [:kafka]
                          :trek/fn    send-user-to-kafka}}


;;; mutation implementation
;;; on implementation, you must provide an id in the args if you use a query
[{:user/create-user {:args  {:user/id             5
                             :user/username       "joe"
                             :user/favorite-color "blue"}

                     :query {:entity/user [:user/username
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

{:users {:trek/state [:state/db]
         :trek/fn    users}}

{:users [{:user [::first-name
                 ::last-name]}]}

{:users [{:id         ""
          :first-name ""
          :last-name  ""}]}

{:query/me {:trek/state [:state/db]
            :trek/desc  "Get the current user"
            :trek/fn    me}}

{:query/me [:first-name
            :last-name]}

(defn spec? [spec]
  (try
    (not (nil? (s/get-spec spec)))
    (catch Exception _ false)))

(defn multimethod? [func]
  (try
    (instance? MultiFn func)
    (catch Exception _ false)))


(s/def :post/title string?)
(s/def :post/author string?)
(s/def :post/post (s/keys :req [::title ::author]))

(s/def :user/first-name string?)
(s/def :user/last-name string?)
(s/def :user/posts (s/coll-of string?))
(s/def :user/id number?)
(s/def :user/user (s/keys :req [:user/first-name :user/last-name]))

(s/def :post/id number?)
(s/def :state/db string?)

;;; keyword spec mocks
(s/def :trek/mock-spec string?)

;;; resolve mock
(defmulti trek-mock-multi :mock/type)
(defmethod trek-mock-multi :test [data] :test)
(defmethod trek-mock-multi :dev [data] :data)


(defn keyword-specs [spec-registry]
  (set (filter keyword? (keys spec-registry))))

(s/def :trek/keyword-spec (s/with-gen spec? #(s/gen (keyword-specs (s/registry)))))

;;; entity config spec
(s/def :trek/id :trek/keyword-spec)
(s/def :trek/spec :trek/keyword-spec)
(s/def :trek/links (s/map-of :trek/keyword-spec keyword?))
(s/def :trek/state (s/coll-of :trek/keyword-spec))
(s/def :trek/desc string?)
(s/def :trek/resolver (s/with-gen multimethod? #(s/gen #{trek-mock-multi})))
(s/def :trek/attrs (s/coll-of :trek/keyword-spec))


(s/def :trek/entity-config (s/keys :req [:trek/id :trek/spec :trek/state :trek/resolver]
                                   :opt [:trek/desc :trek/attrs :trek/links]))

(s/def :trek/entity-map (s/map-of keyword? :trek/entity-config))
(s/def :trek/link-map (s/map-of qualified-keyword? qualified-keyword?))


(def entity-map {:entity/user {:trek/id       :user/id
                               :trek/spec     :user/user
                               :trek/links    {:user/posts :entity/post}
                               :trek/state    [:state/db]
                               :trek/desc     "The user of the system"
                               :trek/resolver user}

                 :entity/post {:trek/id       :post/id
                               :trek/spec     :post/post
                               :trek/state    [:state/db]
                               :trek/desc     "The post of the system"
                               :trek/resolver post}})

(def links-map {:entity/user :entity/user
                :user/posts  :entity/post
                :entity/post :entity/post})

(defn create-result-entry [result path]
  (assoc-in result [path] []))

;;; query
(def query {:query/me [:user/first-name
                       :user/last-name
                       {:user/friends [:user/first-name
                                       :user/last-name]}
                       {:user/posts [:post/title
                                     :post/created-at
                                     {:post/author [:user/first-name
                                                    :user/last-name]}]}]})


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
  {[:entity/user]                          [:user/first-name :user/last-name :user/friends :user/posts]
   [:entity/user :user/friends]            [:user/first-name :user/last-name]
   [:entity/user :user/posts]              [:post/title :post/created-at :post/author]
   [:entity/user :user/posts :post/author] [:user/first-name :user/last-name]})

;{:attr :first-name
; :path [:user]
; :resolver-data {}}
(defn resolve-data [data]
  (assoc data :result [:attr (:attr data)]))

(defn resolve-root [root-path args]
  {})

;;; - issues -
;;; resolving collection data
;;;   if user/friends comes back as a collection, how should I handle it?

(defn execute-query [parsed-query parsed-query-count root-map]
  (let [{:keys [root-path root-args]} root-map
        root-attrs (get parsed-query root-path)
        root-data  (resolve-root root-path root-args)
        done-chan  (async/chan parsed-query-count)]

    (doseq [attr root-attrs]
      (async/go (async/>! done-chan (resolve-data {:attr attr :path root-path :data root-data}))))

    (loop [resolved-data      {}
           parsed-query-count parsed-query-count]

      (cond
        (= parsed-query-count 0)
        resolved-data

        :else
        (let [{:keys [attr path result]} (async/<!! done-chan)
              new-path        (conj path attr)
              remaining-attrs (get parsed-query new-path :trek/not-found)]

          (if (= remaining-attrs :trek/not-found)
            (recur
              (assoc-in resolved-data new-path result)
              (dec parsed-query-count))

            (do
              (doseq [attr remaining-attrs]
                (async/go (async/>! done-chan (resolve-data {:attr attr :path new-path :data result}))))

              (recur
                resolved-data
                (dec parsed-query-count)))))))))


(defn resolve-collection-data [attr parent-data]
  (let [result-chan   (async/chan)
        resolve-count (count (:result parent-data))
        result-data   (vec (repeat resolve-count {}))]

    (doseq [[idx item] (map-indexed (:result parent-data))]
      (async/go (async/>! result-chan (resolve-data {:attr attr :path [idx attr] :data item}))))

    (async/go-loop []
      (let [{:keys [path result]} (async/<! result-chan)
            new-count (dec resolve-count)]

        (assoc-in result-data path result)

        (if (= new-count 0)
          (assoc parent-data :data result-data)
          (recur))))))



(defn execute-query* [parsed-query parsed-query-count root-map]
  (let [{:keys [root-path root-args]} root-map
        root-attrs (get parsed-query root-path)
        root-data  (resolve-root root-path root-args)
        done-chan  (async/chan parsed-query-count)]

    (doseq [attr root-attrs]
      (async/go (async/>! done-chan (resolve-data {:attr attr :path root-path :data root-data}))))

    (loop [resolved-data      {}
           parsed-query-count parsed-query-count]

      (cond
        (= parsed-query-count 0)
        resolved-data

        :else
        (let [{:keys [attr path result] :as done-result} (async/<!! done-chan)
              new-path        (conj path attr)
              remaining-attrs (get parsed-query new-path :trek/not-found)]

          (cond

            (= remaining-attrs :trek/not-found)
            (recur
              (assoc-in resolved-data new-path result)
              (dec parsed-query-count))

            (coll? result)
            (do
              (doseq [attr remaining-attrs]
                (async/go (async/>! done-chan (resolve-collection-data attr done-result))))
              (recur
                resolved-data
                parsed-query-count))

            :else
            (do
              (doseq [attr remaining-attrs]
                (async/go (async/>! done-chan (resolve-data {:attr attr :path new-path :data result}))))

              (recur
                resolved-data
                (dec parsed-query-count)))))))))



;(.indexOf [:a :b :c] :c)
;(assoc-in {:a [{:a 1} {}]} [:a 0 :b] 2)
;(vec (repeat 5 {}))
;[:a :b :c]
;(pp/pprint (execute-query parsed-query 11 {:root-path [:entity/user] :root-args nil}))


;(doseq [attr attrs]
;  (async/go (resolver-data {:attr attr
;                            :path nil
;                            :resolved-data}))))

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


(s/conform :trek/entity-map entity-map)
