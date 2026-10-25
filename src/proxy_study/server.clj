(ns proxy-study.server
  "「何が見えているか」を返す共通バックエンド。"
  (:require [ring.adapter.jetty :as jetty]
            [ring.core.protocols :as protocols]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :refer [memory-store]]
            [proxy-study.redis-store :refer [redis-store]]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.io OutputStream)
           (org.eclipse.jetty.server ConnectionFactory HttpConfiguration
                                     HttpConnectionFactory Server ServerConnector)
           (org.eclipse.jetty.util.thread QueuedThreadPool))
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
  "true なら、http で来たリクエストを https へリダイレクトする(Day 1 Step 3)。"
  (= "true" (env "FORCE_HTTPS" "false")))

(def trust-proxy?
  "true なら、X-Forwarded-Proto を元の通信のスキームとして信じる(Day 1 Step 3)。"
  (= "true" (env "TRUST_PROXY" "false")))

(def workers
  "リクエストを処理できるスレッドの数(Day 2)。Day 0 の Apache の MaxClients にあたる「枠の数」。"
  (Integer/parseInt (env "WORKERS" "48")))

(def access-log?
  "true なら、1リクエスト1行のログを出す。負荷をかけるときは false にする(ログ出力自体が重くなるため)。"
  (= "true" (env "ACCESS_LOG" "true")))

(def session-store-type
  "セッションの置き場所。memory(このプロセスのメモリ)か redis(Day 2 Step 4)。"
  (env "SESSION_STORE" "memory"))

(def redis-uri
  (env "REDIS_URI" "redis://redis:6379"))

;; ---------------------------------------------------------------
;; 計測用の状態
;; ---------------------------------------------------------------

(defonce ^{:doc "Jetty のスレッドプール。-main で作る。/stats で中身を見る。"}
  thread-pool (atom nil))

(defonce ^{:doc "パスごとの、これまでに処理し終えた件数。"}
  counters (atom {}))

(defonce ^{:doc "ハンドラの中にいるリクエストの数(返事の書き込み中は含まない)。"}
  in-handler (atom 0))

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

(defn- param-long [req k default]
  (if-let [v (get-in req [:query-params k])]
    (Long/parseLong v)
    default))

(defn whoami
  "アプリから見えている情報をそのまま返す。"
  [req]
  (json-response
   200
   {:instance         instance
    :remote-addr      (:remote-addr req)
    :scheme           (name (:scheme req))
    :effective-scheme (name (effective-scheme req))
    :server-name      (:server-name req)
    :server-port      (:server-port req)
    :uri              (:uri req)
    :query-string     (:query-string req)
    :headers          (:headers req)}))

(defn- repeated-bytes-body
  "n バイトの 'x' を、8KB ずつソケットに書き込む本文。
   全体をメモリに持たないので、数十MBでも JVM のメモリを圧迫しない。
   書き込みは Jetty のスレッドの中で行われ、相手の受信が遅いとここで止まる。"
  [^long n]
  (reify protocols/StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (let [^OutputStream out out
            chunk (byte-array 8192 (byte 120))]
        (try
          (loop [left n]
            (when (pos? left)
              (let [k (int (min left 8192))]
                (.write out chunk 0 k)
                (recur (- left k)))))
          (finally
            (.close out)))))))

(defn big
  "GET /big?kb=N : N KB の返事を返す(既定 1KB)。遅いクライアントにスレッドを拘束させるため。"
  [req]
  (let [n (* 1024 (param-long req "kb" 1))]
    {:status  200
     :headers {"Content-Type"   "text/plain"
               "Content-Length" (str n)
               "X-Instance"     instance}
     :body    (repeated-bytes-body n)}))

(defn slow
  "GET /slow?ms=N : N ミリ秒待ってから返す(既定 1000ms)。重い処理の代わり。
   待っている間、スレッドを1本占有する(CPU はほぼ使わない)。"
  [req]
  (let [ms (param-long req "ms" 1000)]
    (Thread/sleep ms)
    (json-response 200 {:instance instance :slept-ms ms})))

(defn session-count
  "GET /session : セッションでアクセス回数を数える。"
  [req]
  (let [n (inc (get-in req [:session :count] 0))]
    (-> (json-response 200 {:instance      instance
                            :count         n
                            :session-store session-store-type})
        (assoc :session (assoc (:session req) :count n)))))

(defn stats
  "GET /stats : このインスタンスの今の状態。
   本体とは別の待ち受け(管理用ポート)で返すので、本体の枠が埋まっていても見られる。"
  [_]
  (let [^QueuedThreadPool pool @thread-pool]
    (json-response
     200
     {:instance     instance
      :workers      workers
      ;; 本体の Jetty のスレッドのうち、何かをしているもの。
      ;; acceptor と selector の2本は常に busy なので、それを引いたものが「使用中の枠の数」
      :busy-threads (.getBusyThreads pool)
      :busy-workers (max 0 (- (.getBusyThreads pool) 2))
      ;; スレッドが空くのを待っているリクエストの数(キュー)
      :queued       (.getQueueSize pool)
      ;; ハンドラの中にいる数。返事の書き込み中は含まないので、busy-workers との差が「書き込みで拘束されている数」
      :in-handler   @in-handler
      :completed    @counters})))

(defn routes [req]
  (case [(:request-method req) (:uri req)]
    [:get "/whoami"]  (whoami req)
    [:get "/big"]     (big req)
    [:get "/slow"]    (slow req)
    [:get "/session"] (session-count req)
    (json-response 404 {:instance instance
                        :error    "not found"
                        :uri      (:uri req)})))

(defn admin-routes
  "管理用ポートのハンドラ。/stats だけを返す。"
  [req]
  (if (= [:get "/stats"] [(:request-method req) (:uri req)])
    (stats req)
    (json-response 404 {:instance instance :error "not found" :uri (:uri req)})))

;; ---------------------------------------------------------------
;; ミドルウェア
;; ---------------------------------------------------------------

(defn wrap-force-https
  "force-https? が true で、元の通信が https でないと判断したら https へリダイレクトする。"
  [handler]
  (fn [req]
    (if (and force-https? (not= :https (effective-scheme req)))
      (let [host (get-in req [:headers "host"])
            url  (str "https://" host (:uri req)
                      (when-let [q (:query-string req)] (str "?" q)))]
        {:status 302 :headers {"Location" url} :body ""})
      (handler req))))

(defn wrap-stats
  "ハンドラの中にいる数と、処理し終えた件数(パスごと)を数える。"
  [handler]
  (fn [req]
    (swap! in-handler inc)
    (try
      (handler req)
      (finally
        (swap! in-handler dec)
        (swap! counters update (:uri req) (fnil inc 0))))))

(defn wrap-log
  "1リクエスト1行のログ。接続元と、プロキシが付けたヘッダーを出す。"
  [handler]
  (if-not access-log?
    handler
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
        res))))

(defn- make-session-store []
  (case session-store-type
    "redis" (redis-store redis-uri)
    (memory-store)))

(defn make-app []
  (-> routes
      (wrap-session {:store       (make-session-store)
                     :cookie-name "ps-session"})
      wrap-params
      wrap-force-https
      wrap-stats
      wrap-log))

;; ---------------------------------------------------------------
;; 起動
;; ---------------------------------------------------------------

(defn- make-thread-pool
  "枠の数を数えやすくするため、Jetty のスレッドプールを自分で作る。
   最大スレッド数 = workers + 2 (acceptor 1本 + selector 1本)。"
  ^QueuedThreadPool [pool-name workers]
  (doto (QueuedThreadPool. (int (+ workers 2)) (int (min 8 (+ workers 2))))
    (.setName pool-name)
    ;; 予備スレッドの仕組みを止める(枠の数がずれないように)
    (.setReservedThreads 0)))

(defn- add-connector!
  "acceptor 1本、selector 1本の待ち受けを作る。"
  [^Server server port]
  (let [factory   (HttpConnectionFactory. (HttpConfiguration.))
        connector (ServerConnector. server (int 1) (int 1)
                                    ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
                                    (into-array ConnectionFactory [factory]))]
    (doto connector
      (.setHost "0.0.0.0")   ; コンテナの外(nginx など)から届くように
      (.setPort port))
    (.addConnector server connector)))

(defn -main [& _]
  (let [port       (Integer/parseInt (env "PORT" "3000"))
        admin-port (Integer/parseInt (env "ADMIN_PORT" "9100"))
        pool       (make-thread-pool "jetty" workers)]
    (reset! thread-pool pool)
    (println (format "starting instance=%s port=%d admin-port=%d workers=%d session=%s force-https=%s trust-proxy=%s access-log=%s"
                     instance port admin-port workers session-store-type
                     force-https? trust-proxy? access-log?))
    (flush)
    ;; 管理用ポート(/stats)。本体とは別のスレッドプールで動かす
    (jetty/run-jetty admin-routes
                     {:thread-pool  (make-thread-pool "admin" 2)
                      :http?        false
                      :configurator #(add-connector! % admin-port)
                      :join?        false})
    ;; 本体
    (jetty/run-jetty (make-app)
                     {:thread-pool  pool
                      :http?        false   ; 既定の待ち受けは作らず、configurator で作る
                      :configurator #(add-connector! % port)
                      :join?        true})))
