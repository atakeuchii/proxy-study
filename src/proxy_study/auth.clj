(ns proxy-study.auth
  "Day 8: 署名付きトークンと、認証サービスのハンドラ。
   同じ jar を、認証サービス(INSTANCE=auth)としても、アプリ(api-a)としても使う。"
  (:require [clojure.string :as str])
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (java.security MessageDigest)))

;; ---------------------------------------------------------------
;; ユーザー(本物のサービスでは DB にある)
;; ---------------------------------------------------------------

(def users
  {"yamada" {:password "pass-y" :role "staff"}
   "sato"   {:password "pass-s" :role "staff"}
   "boss"   {:password "pass-b" :role "admin"}})

;; ---------------------------------------------------------------
;; ★ 署名付きトークン
;;   形式: <ユーザー>.<役割>.<有効期限(UNIX 秒)>.<署名>
;;   署名 = HMAC-SHA256(秘密の鍵, "<ユーザー>.<役割>.<有効期限>")
;;   秘密の鍵を持たない人には、正しい署名が作れない
;; ---------------------------------------------------------------

(def ^:private secret
  (or (System/getenv "AUTH_SECRET") "change-me"))

(def ^:private ttl-seconds 1800)

(defn- hmac-hex [^String data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. (.getBytes ^String secret "UTF-8") "HmacSHA256"))
    (->> (.doFinal mac (.getBytes data "UTF-8"))
         (map #(format "%02x" (bit-and % 0xff)))
         (apply str))))

(defn issue-token
  "user と role に署名して、トークンを作る。"
  [user role]
  (let [exp  (+ (quot (System/currentTimeMillis) 1000) ttl-seconds)
        body (str user "." role "." exp)]
    (str body "." (hmac-hex body))))

(defn verify-token
  "トークンを検証する。本物で期限内なら {:user .. :role ..}、そうでなければ nil。"
  [token]
  (when-let [[_ user role exp sig] (some->> token (re-matches #"([^.]+)\.([^.]+)\.(\d+)\.([0-9a-f]+)"))]
    (let [expected (hmac-hex (str user "." role "." exp))]
      (when (and (MessageDigest/isEqual (.getBytes ^String expected) (.getBytes ^String sig)) ; 署名が合うか
                 (< (quot (System/currentTimeMillis) 1000) (Long/parseLong exp)))             ; 期限内か
        {:user user :role role}))))

(defn cookie-value
  "Cookie ヘッダーから、name の値を取り出す。"
  [req name]
  (some->> (get-in req [:headers "cookie"])
           (re-find (re-pattern (str "(?:^|;\\s*)" name "=([^;]+)")))
           second))

;; ---------------------------------------------------------------
;; 認証サービスのハンドラ
;; ---------------------------------------------------------------

(defn login
  "GET /auth/login?user=&password= : 正しければ、トークンを Cookie で渡す。"
  [req json-response]
  (let [user (get-in req [:query-params "user"])
        pw   (get-in req [:query-params "password"])
        u    (get users user)]
    (if (and u (= pw (:password u)))
      (-> (json-response 200 {:login user :role (:role u)})
          (assoc-in [:headers "Set-Cookie"]
                    (str "token=" (issue-token user (:role u)) "; Path=/; HttpOnly")))
      (json-response 401 {:error "invalid user or password"}))))

(defn check
  "GET /auth/check(?require=admin) : nginx の auth_request から呼ばれる。
   Cookie のトークンが本物なら 200 と、誰か(X-Auth-User, X-Auth-Role)を返す。
   require=admin のときは、役割も確かめる(ルールA: 入口でできる認可)。"
  [req]
  (let [who      (verify-token (cookie-value req "token"))
        required (get-in req [:query-params "require"])]
    (cond
      (nil? who)
      {:status 401 :headers {} :body ""}                       ; 誰だか分からない

      (and required (not= required (:role who)))
      {:status 403 :headers {} :body ""}                       ; 誰かは分かったが、権限がない

      :else
      {:status  200
       :headers {"X-Auth-User" (:user who)
                 "X-Auth-Role" (:role who)}
       :body    ""})))
