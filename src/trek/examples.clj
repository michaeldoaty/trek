(ns trek.examples
  (:require [clojure.spec :as s]))

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
                   {:friends [:first-name
                              :last-name]}]})

(declare all-users user-friends user-posts find-user authenticated context users)

;;; user multimethod

(defmulti user (fn [path _ _] path))

(defmethod user :user [_ _ context]
  (find-user (:args context)))

(defmethod user [:user :first-name] [_ _ _]
  nil)

(defmethod user [:user] [_ users context]
  (map #(select-keys % [:first-name :last-name])))

(defmethod user [:user :friends] [_ users context]
  (map user-friends users))

(defmethod user [:user :posts] [_ users context]
  (map user-posts (:args context)))

(declare welcome-email welcome-text execute-query execute-insert send-user-to-kafka)

(defn get-user [database username]
  (execute-query (:connection database)
                 "SELECT * FROM users WHERE username = ?"
                 username))

(defn create-user [database username favorite-color]
  (execute-insert (:connection database)
                  "INSERT INTO users (username, favorite_color)"
                  username favorite-color))


;; mutation map
;; function spec required to have args and ret
{:user/create-user   {:state    [:db]
                      :resolver create-user}

 :user/send-to-kafka {:state    [:kafka]
                      :resolver send-user-to-kafka}}


;;; mutation implementation
;;; on implementation, you must provide an id in the args if you use a query
[{:user/create-user {:args  {:user/id             5
                             :user/username       "joe"
                             :user/favorite-color "blue"}

                     :query {:user [:user/username
                                    :user/favorite-color]}}}]


;;; post multimethod

(defmulti post (fn [path _ _] path))

(defmethod post :post [_ _ context]
  nil)

(defmethod post [:post] [path _ _]
  path)



;;; root multimethod

(defmulti root (fn [path _ _] path))

(defmethod root [:root :users] [path _ _]
  [{:first-name "Magic"
    :last-name  "Johnson"}
   {:first-name "Chris"
    :last-name  "Weber"}])

(defmethod root [:root :posts] [path _ _]
  path)

(defn uuid []
  (java.util.UUID/randomUUID))

(declare first-name last-name)

{[:user :first-name] first-name
 [:user :last-name]  last-name}

;;; Thinking about change this map's name because it needs
;;; to represent things such as search and other things that
;;; are not "entities".

;;; Thinking about getting rid of links because they can be generated
;;; via spec destructing

;;; query implementation

;;; change resolver name because it needs to be different from the
;;; resolver on entity

;;; restrict the query function to a coll of a spec or keys
;;; with a value of a valid entity for now

{:users {:trek/state [:db]
         :trek/fn    users}}

{:users [{:user [::first-name
                 ::last-name]}]}

(def entity-map {:user {:trek/id       :id
                        :trek/spec     ::user
                        :trek/state    [:db]
                        :trek/desc     "The user of the system"
                        :trek/resolver user}

                 :post {:trek/id       :id
                        :trek/spec     ::post
                        :trek/state    [:db]
                        :trek/desc     "The post of the system"
                        :trek/resolver post}

                 :root {:trek/links    {:users :user
                                        :posts :post}
                        :trek/state    [:db]
                        :trek/resolver root}})



;;; query
(def query {:user [:first-name
                   :last-name
                   {:friends [:first-name
                              :last-name]}]})


(def users-query {:root [{:users [:first-name
                                  :last-name]}]})


;(framework/execute-query users-query entity-map)
;(time (framework/execute-query query entity-map))

;(def query {:user [:first-name
;                   :last-name
;                   {:friends [:first-name
;                              :last-name
;                              {:posts [:title
;                                         :objective]}]}]})

(s/def ::number int?)
(s/def ::mobile boolean?)

(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")
(s/def ::email-type (s/and string? #(re-matches email-regex %)))

(s/def ::acctid int?)
(s/def ::first-name string?)
(s/def ::last-name string?)
(s/def ::email ::email-type)
(s/def ::phone (s/keys :req [::number ::mobile]))

(s/def ::person (s/keys :req [::first-name ::last-name ::phone]))

(s/form ::person)
(s/form ::phone)
;(s/exercise ::person)
