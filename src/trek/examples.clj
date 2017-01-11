(ns trek.examples)

(def query {:user [:first-name
                   :last-name
                   {:friends [:first-name
                              :last-name]}]})

(declare all-users user-friends user-posts find-user authenticated context)


;;; user multimethod

(defmulti user (fn [path _ _] path))

(defmethod user :entity [_ _ context]
  (find-user (:args context)))

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
{:user/create-user   {:entity    :entity/user
                      :spec-args [:user/username :user/favorite-color]
                      :state     [:db]
                      :resolver  create-user}

 :user/send-to-kafka {:entity    :entity/user
                      :spec-args [:user/id]
                      :state     [:kafka]
                      :resolver  send-user-to-kafka}}


;;; mutation implementation
[{:signup/click-enter {:mutation :user/create-user
                       :args     {:user/username       "joe"
                                  :user/favorite-color "blue"}
                       :query    {:user [:user/username
                                         :user/favorite-color]}}}]




[:user/create-user
 :user/send-to-kafka]

[{:user/create-user #{:create}}
 {:user/send-to-kafka #{:create}}]

;; use of mutation
{:user/create-user {:mutations [:user/create-user
                                :user/send-to-kafka]}}


{[:user/create-user
  :user/send-to-kafka]
 {:user [:first-name
         :last-name]}}


;[[:user/welcome-email]
; [:user/welcome-text]]
;
;{[:user/welcome-email] welcome-email
; [:user/welcome-text]  welcome-text}




{:user [:user/welcome-email user]}
{:user [:user/welcome-text user]}




;;; post multimethod

(defmulti post (fn [path _ _] path))

(defmethod post :entity [_ _ context]
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


(def entity-map {:user   {:trek/id       :id
                          :trek/spec     ::user
                          :trek/links    {:posts :post
                                          :friends :user}
                          :trek/state    [:db]
                          :trek/desc     "The user of the system"
                          :trek/resolver user}

                 :post {:trek/id       :id
                          :trek/spec     ::post
                          :trek/state    [:db]
                          :trek/desc     "The post of the system"
                          :trek/resolver post}

                 :root   {:trek/links    {:users   :user
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

{:user [{:friends [:first-name
                   :last-name]}]}

;(def query {:user [:first-name
;                   :last-name
;                   {:friends [:first-name
;                              :last-name
;                              {:posts [:title
;                                         :objective]}]}]})
