(ns trek.examples
  (:require [clojure.spec :as s]
            [trek.parser :as parser]))

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


(def entity-map {:user {:trek/id       :id
                        :trek/spec     ::user
                        :trek/links    {:posts :post}
                        :trek/state    [:db]
                        :trek/desc     "The user of the system"
                        :trek/resolver user}

                 :post {:trek/id       :id
                        :trek/spec     ::post
                        :trek/state    [:db]
                        :trek/desc     "The post of the system"
                        :trek/resolver post}})

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


(defn parser [query]
  (let [root-key   (apply key query)
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
  {[:me]                [:first-name :last-name :friends :posts]
   [:me :friends]       [:first-name :last-name]
   [:me :posts]         [:title :created-at :author]
   [:me :posts :author] [:first-name :last-name]})



