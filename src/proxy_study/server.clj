(ns proxy-study.server
  "「何が見えているか」を返す共通バックエンド。"
  (:require [ring.adapter.jetty :as jetty]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:gen-class))

;; ---------------------------------------------------------------
;; 設定(環境変数)
;; ---------------------------------------------------------------

(defn- env [k default]
  (or (System/getenv k) default))

(def instance
  "どのインスタンスが返事をしたかを見分けるための名前。"
  (env "INSTANCE" "local"))

(def force-https?
  "true なら、http で来たリクエストを https へリダイレクトする(Step 3)。"
  (= "true" (env "FORCE_HTTPS" "false")))

(def trust-proxy?
  "true なら、X-Forwarded-Proto を元の通信のスキームとして信じる(Step 3)。"
  (= "true" (env "TRUST_PROXY" "false")))

;; ---------------------------------------------------------------
;; ハンドラ
;; ---------------------------------------------------------------

(defn effective-scheme
  "アプリが「元の通信のスキーム」だと判断するもの。
   trust-proxy? が false なら、自分への接続のスキーム(:scheme)だけを見る。"
  [req]
  (if trust-proxy?
    (or (some-> (get-in req [:headers "x-forwarded-proto"]) keyword)
        (:scheme req))
    (:scheme req)))

(defn- json-response [status body]
  {:status  status
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/generate-string body {:pretty true})})

(defn whoami
  "アプリから見えている情報をそのまま返す。"
  [req]
  (json-response
   200
   {:instance         instance
    :remote-addr      (:remote-addr req)       ; TCP 接続の相手の IP
    :scheme           (name (:scheme req))     ; 自分への接続のスキーム
    :effective-scheme (name (effective-scheme req))
    :server-name      (:server-name req)
    :server-port      (:server-port req)
    :uri              (:uri req)
    :query-string     (:query-string req)
    :headers          (:headers req)}))

(defn routes [req]
  (case [(:request-method req) (:uri req)]
    [:get "/whoami"] (whoami req)
    (json-response 404 {:instance instance
                        :error    "not found"
                        :uri      (:uri req)})))

;; ---------------------------------------------------------------
;; ミドルウェア
;; ---------------------------------------------------------------

(defn wrap-force-https
  "force-https? が true で、元の通信が https でないと判断したら https へリダイレクトする。
   ブラウザにキャッシュされないよう、301 ではなく 302 を使う。"
  [handler]
  (fn [req]
    (if (and force-https? (not= :https (effective-scheme req)))
      (let [host (get-in req [:headers "host"])
            url  (str "https://" host (:uri req)
                      (when-let [q (:query-string req)] (str "?" q)))]
        {:status 302 :headers {"Location" url} :body ""})
      (handler req))))

(defn wrap-log
  "1リクエスト1行のログ。接続元と、プロキシが付けたヘッダーを出す。"
  [handler]
  (fn [req]
    (let [res (handler req)]
      (println (format "[%s] %s %s from=%s host=%s xff=%s xfp=%s -> %d"
                       instance
                       (str/upper-case (name (:request-method req)))
                       (:uri req)
                       (:remote-addr req)
                       (get-in req [:headers "host"])
                       (get-in req [:headers "x-forwarded-for"])
                       (get-in req [:headers "x-forwarded-proto"])
                       (:status res)))
      (flush)
      res)))

(def app
  (-> routes
      wrap-force-https
      wrap-log))

;; ---------------------------------------------------------------
;; 起動
;; ---------------------------------------------------------------

(defn -main [& _]
  (let [port (Integer/parseInt (env "PORT" "3000"))]
    (println (format "starting instance=%s port=%d force-https=%s trust-proxy=%s"
                     instance port force-https? trust-proxy?))
    (flush)
    ;; コンテナの外(nginx)から届くよう、0.0.0.0 で待ち受ける
    (jetty/run-jetty app {:host "0.0.0.0" :port port :join? true})))
