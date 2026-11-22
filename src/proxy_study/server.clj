(ns proxy-study.server
  "「何が見えているか」を返す共通バックエンド。"
  (:require [ring.adapter.jetty :as jetty]
            [ring.core.protocols :as protocols]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :refer [memory-store]]
            [ring.websocket :as ws]
            [proxy-study.redis-store :refer [redis-store]]
            [taoensso.carmine :as car]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import (java.io InputStream OutputStream)
           (java.time LocalTime)
           (java.time.format DateTimeFormatter)
           (org.eclipse.jetty.io ConnectionStatistics)
           (org.eclipse.jetty.server ConnectionFactory HttpConfiguration
                                     HttpConnectionFactory Server ServerConnector)
           (org.eclipse.jetty.server.handler StatisticsHandler)
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

(def version
  "アプリの版(Day 4)。旧版 v1 と新版 v2 を見分けるため。"
  (env "VERSION" "v1"))

(def graceful?
  "true なら、SIGTERM を受けたときにグレースフルシャットダウンする(Day 4)。"
  (= "true" (env "GRACEFUL" "false")))

;; ---------------------------------------------------------------
;; 計測用の状態
;; ---------------------------------------------------------------

(defonce ^{:doc "Jetty のスレッドプール。-main で作る。/stats で中身を見る。"}
  thread-pool (atom nil))

(defonce ^{:doc "パスごとの、これまでに処理し終えた件数。"}
  counters (atom {}))

(defonce ^{:doc "ハンドラの中にいるリクエストの数(返事の書き込み中は含まない)。"}
  in-handler (atom 0))

(declare render-counts routes*)   ; Day 6: 後ろで定義する

(defonce ^{:doc "本体の待ち受けが受け付けた接続の数を数える部品(Day 5)。"}
  conn-stats (atom nil))

(defonce ^{:doc "本体の待ち受け(ServerConnector)。障害注入の down で止める・再開する。"}
  main-connector (atom nil))

;; ---------------------------------------------------------------
;; 障害注入(Day 3)
;; 管理用ポートの /fault で切り替える。本体のリクエストに、次のように効く。
;;   error-rate : この確率で、処理せずに即座に 500 を返す(0.0〜1.0)
;;   delay-ms   : 処理を「終えた後」、返事を返す前にこの時間だけ待つ
;;                (注文なら、注文は作られたのに返事だけが遅れる状態になる)
;;   down       : 本体の待ち受けを閉じる(プロセスは生きたまま。接続は connection refused になる)
;; ---------------------------------------------------------------

(def ^:private no-fault {:error-rate 0.0 :delay-ms 0 :down false})

(defonce ^{:doc "今の障害注入の状態。"}
  fault (atom no-fault))

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
    :version          version
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
    (json-response 200 {:instance instance :version version :slept-ms ms})))

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
      ;; Day 5: これまでに受け付けた TCP 接続の数(nginx が接続を使い回すと、増え方が遅くなる)
      :connections-total (some-> ^ConnectionStatistics @conn-stats .getConnectionsTotal)
      ;; Day 6: 記事ごとの描画回数
      :renders      @render-counts
      :completed    @counters})))

;; ---------------- Day 5: 長い接続と大きなデータ ----------------

(def ^:private hms (DateTimeFormatter/ofPattern "HH:mm:ss"))

(defn- sse-body
  "SSE の本文。n 件のイベントを interval-ms ごとに書き、1件ごとに flush する。
   heartbeat-ms > 0 なら、イベントの間にその間隔でコメント行(: ping)を書く。"
  [n interval-ms heartbeat-ms]
  (reify protocols/StreamableResponseBody
    (write-body-to-stream [_ _ out]
      (let [^OutputStream out out
            write! (fn [^String s]
                     (.write out (.getBytes s "UTF-8"))
                     ;; ★ 1件ごとに flush する。しないと Jetty の出力バッファ(32KB)に溜まってしまう
                     (.flush out))
            ;; ms ミリ秒待つ。heartbeat があれば、その間隔で ping を書く
            wait! (fn [ms]
                    (if (pos? heartbeat-ms)
                      (loop [left ms]
                        (when (pos? left)
                          (let [t (min left heartbeat-ms)]
                            (Thread/sleep t)
                            (when (and (= t heartbeat-ms) (< t left))
                              (write! ": ping\n\n"))
                            (recur (- left t)))))
                      (Thread/sleep ms)))]
        (try
          (doseq [i (range 1 (inc n))]
            (write! (str "data: " (json/generate-string
                                   {:n i :at (.format (LocalTime/now) hms) :instance instance})
                         "\n\n"))
            (when (< i n) (wait! interval-ms)))
          (catch java.io.IOException _
            ;; 相手(nginx やブラウザ)が接続を切った
            (println (format "[%s] /events: connection closed by peer" instance))
            (flush))
          (finally
            (.close out)))))))

(defn events
  "GET /events?n=&interval-ms=&heartbeat-ms=&accel= : SSE でイベントを流す。
   accel=no なら、返事に X-Accel-Buffering: no を付ける(nginx にこの返事だけバッファリングをやめさせる)。"
  [req]
  (let [n            (param-long req "n" 30)
        interval-ms  (param-long req "interval-ms" 1000)
        heartbeat-ms (param-long req "heartbeat-ms" 0)]
    {:status  200
     :headers (cond-> {"Content-Type"  "text/event-stream"
                       "Cache-Control" "no-cache"}
                (= "no" (get-in req [:query-params "accel"])) (assoc "X-Accel-Buffering" "no"))
     :body    (sse-body n interval-ms heartbeat-ms)}))

(defn websocket
  "GET /ws : WebSocket のエコー。受け取ったメッセージを、そのまま送り返す。
   WebSocket への切り替えの依頼(Upgrade ヘッダー)が届いていなければ、426 を返す。"
  [req]
  (if (ws/upgrade-request? req)
    {::ws/listener
     {:on-open    (fn [_] (println (format "[%s] /ws: opened" instance)) (flush))
      :on-message (fn [socket msg] (ws/send socket (str "echo from " instance ": " msg)))
      :on-close   (fn [_ code reason]
                    (println (format "[%s] /ws: closed code=%s reason=%s" instance code reason))
                    (flush))}}
    (json-response 426 {:instance instance
                        :error    "WebSocket upgrade request expected"
                        :received {:upgrade    (get-in req [:headers "upgrade"])
                                   :connection (get-in req [:headers "connection"])}})))

(defn upload
  "POST /upload : 本文を読み切って、そのバイト数を返す(中身は捨てる)。"
  [req]
  (let [^InputStream in (:body req)
        buf (byte-array 65536)
        n   (if in
              (loop [total 0]
                (let [k (.read in buf)]
                  (if (neg? k) total (recur (+ total k)))))
              0)]
    (json-response 200 {:instance instance :received-bytes n})))

;; ---------------- 注文(Day 3) ----------------
;; 注文は Redis に置く(3台で共有する「データベース」の代わり)。
;;   order:seq      注文番号の連番
;;   orders         作られた注文の一覧(JSON の文字列のリスト)
;;   idem:<キー>    冪等キーごとの処理結果("processing" または返事の JSON)

(def ^:private redis-conn
  {:pool (car/connection-pool {}) :spec {:uri redis-uri}})

(defmacro ^:private wcar* [& body]
  `(car/wcar redis-conn ~@body))

(def ^:private idem-ttl-seconds
  "冪等キーを覚えておく時間(24時間)。"
  86400)

(defn- create-order!
  "注文を1件作って、その内容を返す。"
  [req]
  (let [id    (wcar* (car/incr "order:seq"))
        order {:order-id        id
               :instance        instance
               :request-id      (get-in req [:headers "x-request-id"])
               :idempotency-key (get-in req [:headers "idempotency-key"])}]
    (wcar* (car/rpush "orders" (json/generate-string order)))
    order))

(defn create-order
  "POST /orders : 注文を1件作る。
   Idempotency-Key ヘッダーがあれば、同じキーで2回目以降に来たときは注文を作らず、1回目の結果を返す。"
  [req]
  (if-let [k (get-in req [:headers "idempotency-key"])]
    (let [rk      (str "idem:" k)
          ;; キーを「処理中」として登録する。NX = まだ無いときだけ登録できる
          claimed (wcar* (car/set rk "processing" "NX" "EX" idem-ttl-seconds))]
      (if (= "OK" claimed)
        ;; 初めてのキー → 注文を作り、結果を覚えておく
        (let [order (create-order! req)]
          (wcar* (car/set rk (json/generate-string order) "EX" idem-ttl-seconds))
          (json-response 201 (assoc order :replayed false)))
        ;; 見たことのあるキー
        (let [saved (wcar* (car/get rk))]
          (if (= "processing" saved)
            ;; 1回目がまだ処理中
            (json-response 409 {:instance instance :error "same key is being processed"})
            ;; 1回目の結果をそのまま返す(注文は作らない)
            (json-response 200 (assoc (json/parse-string saved true)
                                      :replayed true
                                      :answered-by instance))))))
    (json-response 201 (create-order! req))))

(defn list-orders
  "GET /orders : これまでに作られた注文の数と、最後の10件。"
  [_]
  (let [total  (wcar* (car/llen "orders"))
        latest (wcar* (car/lrange "orders" -10 -1))]
    (json-response 200 {:total  total
                        :latest (mapv #(json/parse-string % true) latest)})))

(defn reset-orders
  "DELETE /orders : 注文と冪等キーを全部消す(実験のやり直し用)。"
  [_]
  (let [idem-keys (wcar* (car/keys "idem:*"))]
    (wcar* (car/del "orders" "order:seq"))
    (when (seq idem-keys)
      (wcar* (apply car/del idem-keys)))
    (json-response 200 {:reset true})))

;; ---------------- Day 6: 記事ページ ----------------

(def render-ms
  "記事の描画にかかる時間(ミリ秒)。重いページの代わり。"
  (Long/parseLong (env "RENDER_MS" "200")))

(defonce ^{:doc "記事ごとの、これまでに描画した回数(= アプリまで届いた回数)。"}
  render-counts (atom {}))

(defn- login-user
  "Cookie の user=... から、ログイン中のユーザー名を取り出す(なければ nil)。"
  [req]
  (some->> (get-in req [:headers "cookie"])
           (re-find #"(?:^|;\s*)user=([^;]+)")
           second))

(defn article
  "GET /articles/:id : 記事ページ。描画に render-ms かかる。
   ★ 右上の「ログイン中」は、Cookie から見ている人ごとに変わる。
   ★ それなのに、どの返事にも Cache-Control: public, max-age=10 を付けている(Step 4 の事故の原因)。"
  [req]
  (let [id    (subs (:uri req) (count "/articles/"))
        user  (login-user req)
        n     (get (swap! render-counts update id (fnil inc 0)) id)]
    (Thread/sleep render-ms)                            ; 重い描画の代わり
    {:status  200
     :headers {"Content-Type"  "text/plain; charset=utf-8"
               "Cache-Control" "public, max-age=10"}    ; 「誰の返事として、10秒使い回してよい」
     :body    (str "記事 " id "\n"
                   "ログイン中: " (or user "(ゲスト)") "\n"
                   "描画したサーバ: " instance "\n"
                   "描画した時刻: " (.format (LocalTime/now) hms) "\n"
                   "この記事の描画回数: " n "\n")}))

(defn routes [req]
  (if (and (= :get (:request-method req))
           (str/starts-with? (:uri req) "/articles/"))
    (article req)                                       ; Day 6
    (routes* req)))

(defn routes* [req]
  (case [(:request-method req) (:uri req)]
    [:get "/whoami"]     (whoami req)
    [:get "/big"]        (big req)
    [:get "/slow"]       (slow req)
    [:post "/slow"]      (slow req)   ; Day 4: nginx がやり直さない(POST)ことを利用して、切れたリクエストを数える
    [:get "/session"]    (session-count req)
    [:post "/orders"]    (create-order req)
    [:get "/orders"]     (list-orders req)
    [:delete "/orders"]  (reset-orders req)
    [:get "/events"]     (events req)      ; Day 5
    [:get "/ws"]         (websocket req)   ; Day 5
    [:post "/upload"]    (upload req)      ; Day 5
    (json-response 404 {:instance instance
                        :error    "not found"
                        :uri      (:uri req)})))

;; ---------------- 管理用ポート ----------------

(defn- set-down!
  "本体の待ち受けを閉じる(true)・開き直す(false)。"
  [down?]
  (let [^ServerConnector c @main-connector]
    (if down?
      (when (.isStarted c) (.stop c))
      (when-not (.isStarted c) (.start c)))))

(defn update-fault
  "POST /fault?error-rate=&delay-ms=&down= : 障害注入の状態を変える。指定したものだけ変わる。
   POST /fault?reset=true で、すべて元に戻す。"
  [req]
  (let [p   (:query-params req)
        new (if (= "true" (get p "reset"))
              no-fault
              (cond-> @fault
                (get p "error-rate") (assoc :error-rate (Double/parseDouble (get p "error-rate")))
                (get p "delay-ms")   (assoc :delay-ms (Long/parseLong (get p "delay-ms")))
                (get p "down")       (assoc :down (= "true" (get p "down")))))]
    (set-down! (:down new))
    (reset! fault new)
    (println (format "[%s] fault changed: %s" instance (pr-str new)))
    (flush)
    (json-response 200 (assoc new :instance instance))))

(defn admin-routes*
  [req]
  (case [(:request-method req) (:uri req)]
    [:get "/stats"]  (stats req)
    [:get "/fault"]  (json-response 200 (assoc @fault :instance instance))
    [:post "/fault"] (update-fault req)
    (json-response 404 {:instance instance :error "not found" :uri (:uri req)})))

(def admin-routes
  "管理用ポートのハンドラ。/stats と /fault を返す。"
  (wrap-params admin-routes*))

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

(defn wrap-fault
  "障害注入。error-rate の確率で処理せずに 500 を返す。
   そうでなければ処理して、返事を返す前に delay-ms だけ待つ。"
  [handler]
  (fn [req]
    (let [{:keys [error-rate delay-ms]} @fault]
      (if (< (rand) error-rate)
        (json-response 500 {:instance instance :error "injected failure"})
        (let [res (handler req)]
          (when (pos? delay-ms)
            (Thread/sleep delay-ms))
          res)))))

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
        (println (format "[%s] %s %s from=%s host=%s xff=%s xfp=%s rid=%s -> %d"
                         instance
                         (str/upper-case (name (:request-method req)))
                         (:uri req)
                         (:remote-addr req)
                         (get-in req [:headers "host"])
                         (get-in req [:headers "x-forwarded-for"])
                         (get-in req [:headers "x-forwarded-proto"])
                         (get-in req [:headers "x-request-id"])   ; Day 3: nginx が付けるリクエスト ID
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
      wrap-fault          ; Day 3: 障害注入(500 を返した場合も、下の2つで数え・ログに出す)
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
  "acceptor 1本、selector 1本の待ち受けを作って、それを返す。"
  ^ServerConnector [^Server server port]
  (let [factory   (HttpConnectionFactory. (HttpConfiguration.))
        connector (ServerConnector. server (int 1) (int 1)
                                    ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
                                    (into-array ConnectionFactory [factory]))]
    (doto connector
      (.setHost "0.0.0.0")   ; コンテナの外(nginx など)から届くように
      (.setPort port))
    (.addConnector server connector)
    connector))

(defn -main [& _]
  (let [port       (Integer/parseInt (env "PORT" "3000"))
        admin-port (Integer/parseInt (env "ADMIN_PORT" "9100"))
        pool       (make-thread-pool "jetty" workers)]
    (reset! thread-pool pool)
    (println (format "starting instance=%s version=%s port=%d admin-port=%d workers=%d session=%s graceful=%s access-log=%s"
                     instance version port admin-port workers session-store-type
                     graceful? access-log?))
    ;; SIGTERM を受けたことをログに出す(graceful? によらず)
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. #(do (println (format "[%s] SIGTERM received (graceful=%s, in-handler=%d)"
                                                     instance graceful? @in-handler))
                                    (flush))))
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
                      :configurator (fn [^Server server]
                                      (reset! main-connector (add-connector! server port))
                                      ;; Day 5: 受け付けた接続の数を数える
                                      (let [cs (ConnectionStatistics.)]
                                        (.addBean ^ServerConnector @main-connector cs)
                                        (reset! conn-stats cs))
                                      ;; ★ Day 4: グレースフルシャットダウン
                                      (when graceful?
                                        ;; 処理中のリクエストを数える部品を、アプリの手前に挟む
                                        (.setHandler server (doto (StatisticsHandler.)
                                                              (.setHandler (.getHandler server))))
                                        ;; 止めるときは、処理中のリクエストが終わるのを最大30秒待つ
                                        (.setStopTimeout server 30000)
                                        ;; SIGTERM を受けたら、上の手順で止める
                                        ;; (新しい接続の受け付けをやめる → 処理中のものを待つ → 止まる)
                                        (.setStopAtShutdown server true)))
                      :join?        true})
    (println (format "[%s] stopped" instance))
    (flush)))
