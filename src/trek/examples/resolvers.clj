(ns trek.examples.resolvers)

;;; --------------------- ;;;
;;; ------- user -------- ;;;
;;; --------------------- ;;;
(defmulti user (fn [path _ _] path))

(defmethod user :user/first-name [_ user context]
  (:first-name user))

(defmethod user :user/last-name [_ user context]
  (:last-name user))

(defmethod user :user/friends [_ user context]
  (:friends user))

(defmethod user :user/posts [_ user context]
  (:posts user))



;;; --------------------- ;;;
;;; ------- post -------- ;;;
;;; --------------------- ;;;

(defmulti post (fn [path _ _] path))

(defmethod post :post/author [path entity _]
  (:author entity))

(defmethod post :post/title [path entity _]
  (:title entity))

(defmethod post :post/created-at [path entity _]
  (:created-at entity))

