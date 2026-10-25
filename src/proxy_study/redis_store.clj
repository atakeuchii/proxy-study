(ns proxy-study.redis-store
  "Ring のセッションを Redis に保存するストア(Day 2 Step 4)。
   どのインスタンスに届いても、同じセッション ID から同じデータを引ける。"
  (:require [ring.middleware.session.store :as store]
            [taoensso.carmine :as car])
  (:import (java.util UUID)))

(def ^:private ttl-seconds
  "セッションの有効期限。最後に書き込んでから30分で消える。"
  1800)

(defn- redis-key [k]
  (str "session:" k))

(deftype RedisStore [wcar-opts]
  store/SessionStore
  (read-session [_ k]
    (when k
      (car/wcar wcar-opts (car/get (redis-key k)))))
  (write-session [_ k data]
    ;; 初めてのアクセスなら k は nil。新しいセッション ID を作る
    (let [k (or k (str (UUID/randomUUID)))]
      (car/wcar wcar-opts (car/setex (redis-key k) ttl-seconds data))
      k))
  (delete-session [_ k]
    (car/wcar wcar-opts (car/del (redis-key k)))
    nil))

(defn redis-store [uri]
  (->RedisStore {:pool (car/connection-pool {})
                 :spec {:uri uri}}))
