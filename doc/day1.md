# Day 1 レポート — 複数のアプリを1つの入口から公開する

## 1. 目的

### 1.1 背景（どの時代の困りごとか）

- **時代①（Webが重くなる）**：「客が見る住所」と「実際に働く機械」を切り離す。クライアントは入口だけを知り、裏の構成を知らない
- **時代②（SSLの普及）**：暗号処理を前段に集め（TLS終端）、前段が HTTP の中身を読めるようにする（L7 での振り分け）
- **時代④（マイクロサービス）**：複数のサービスを1つの入口の下に並べ、パスやホスト名で振り分ける

### 1.2 場面

自宅サーバに API が2つとフロント（静的ファイル）があり、それぞれのポートを直接公開している。フロントから API を呼ぶと CORS エラーになる。いしなび（フロントは GitHub、API は Cloud Run の別ドメイン）と同じ構図。

### 1.3 確かめたいこと

1. 別オリジンの呼び出しで CORS エラーが起きるとき、誰が何を止めているのか
2. nginx を入口にすると、なぜ CORS が消えるのか
3. プロキシを挟むと、アプリから見える**接続元・ホスト名・スキーム**がどう変わるのか
4. TLS 終端の後ろで、なぜリダイレクトループが起きるのか、どう直すのか
5. L4 と L7 のプロキシで、アプリから見える情報と、できることがどう違うのか

---

## 2. 手法

### 2.1 環境

- 実行環境：Windows の WSL（Ubuntu）上の Docker。ブラウザは Windows 側の Firefox
- 構成管理：Docker Compose
- 入口：nginx（`nginx:stable-alpine`）
- バックエンド：Clojure（ring-jetty）の共通バックエンド。`GET /whoami` で、アプリから見えている情報（`remote-addr`、`scheme`、受け取ったヘッダーなど）を JSON で返す
- 証明書：mkcert（Windows 側で認証局を登録し、証明書を WSL 側にコピー）

### 2.2 アプリの設定（環境変数）

| 変数 | 意味 |
|---|---|
| `INSTANCE` | インスタンス名（`a` / `b`）。どちらが返事をしたかを見分ける |
| `FORCE_HTTPS` | `true` なら、https でないと判断したリクエストを https へ 302 リダイレクトする |
| `TRUST_PROXY` | `true` なら、`X-Forwarded-Proto` を元の通信のスキームとして信じる |

### 2.3 手順

| Step | 構成 | 内容 |
|---|---|---|
| 1 | 素朴な構成（`compose.naive.yml`）：front:8000、api-a:3001、api-b:3002 を直接公開 | curl とブラウザで `/whoami` を呼び、CORS エラーを再現する |
| 2 | nginx を唯一の入口にする（`compose.yml`） | パス（`/api/a/`、`/api/b/`）とホスト名（`api-a.localhost`）で振り分ける。`proxy_pass` の末尾の `/` の有無も比べる |
| 3 | nginx で TLS 終端（443） | `FORCE_HTTPS` と `TRUST_PROXY` を切り替えて、リダイレクトループを再現して直す |
| 4 | nginx の `stream`（L4）：9001→api-a、9002→api-b | L7 と同じリクエストを送り、見える情報を比べる。偽の `X-Forwarded-For` も送る |

nginx が付けるヘッダー（L7）：

```nginx
proxy_set_header Host              $host;
proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host  $host;
```

---

## 3. 結果

### 3.1 Step 1：素朴な構成

**curl（`http://localhost:3001/whoami`）**

| 項目 | 値 |
|---|---|
| `remote-addr` | `172.22.0.1` |
| `host` | `localhost:3001` |
| `x-forwarded-for` / `x-forwarded-proto` | なし |

**ブラウザ（`http://localhost:8000` のページから `http://localhost:3001/whoami` を fetch）**

- コンソール：「クロスオリジン要求をブロックしました: 同一生成元ポリシーにより…読み込みは拒否されます」
- api-a のログ：`[a] GET /whoami from=172.22.0.1 host=localhost:3001 xff=null xfp=null -> 200`

### 3.2 Step 2：nginx を入口にする

**途中で起きた問題**：WSL の curl だけが 404 を返した。`curl -v` の応答ヘッダーが `Server: Apache/2.4.52 (Ubuntu)`、`ss -tlnp` で `apache2` が `*:80` を待ち受けていた。使っていない Apache だったので削除し、解消した。

**パスでの振り分け（`http://localhost/api/a/whoami`、curl・ブラウザとも）**

| 項目 | 値 |
|---|---|
| `instance` | `a` |
| `remote-addr` | `172.22.0.4` |
| `host` | `localhost` |
| `x-forwarded-for` | `172.22.0.1` |
| `x-forwarded-proto` | `http` |
| `uri` | `/whoami` |
| `sec-fetch-site`（ブラウザのみ） | `same-origin` |

**ホスト名での振り分け（`curl -H "Host: api-a.localhost" http://localhost/whoami`）**：`instance: a`、`host: api-a.localhost`、`server-name: api-a.localhost`

**その他**

- 3001番への直接接続：接続できない（ブラウザの①は `TypeError: NetworkError`）
- `proxy_pass` の末尾の `/` を外した場合（予想）：api-a に `/api/a/whoami` のまま届き、api-a が not found を返す
- ブラウザからのリクエストに、このアプリが発行していない別アプリの Cookie（`localhost` のもの）も届いていた

### 3.3 Step 3：TLS 終端とリダイレクトループ

| 手順 | アプリの設定 | リクエスト | 結果 |
|---|---|---|---|
| 3-1 | 既定 | `https://localhost/api/a/whoami` | 200。`scheme: http`、`effective-scheme: http`、`x-forwarded-proto: https` |
| 3-2 | `FORCE_HTTPS=true` | `https://api-a.localhost/whoami`（`-L --max-redirs 5`） | 302 が続き、curl が上限の5回で打ち切った |
| 3-3 | `FORCE_HTTPS=true` | `https://localhost/api/a/whoami` | 302、`Location: https://localhost/whoami`（`/api/a` が消えた） |
| 3-4 | `FORCE_HTTPS=true`、`TRUST_PROXY=true` | 3-2、3-3 と同じ | ともに 200 |
| 3-4 | 同上 | `http://localhost/api/a/whoami` | 302 |

### 3.4 Step 4：L4 と L7 の比較

| 項目 | L4（`localhost:9001/whoami`） | L7（`localhost/api/a/whoami`） |
|---|---|---|
| `remote-addr` | `172.22.0.4` | `172.22.0.4` |
| `host` | `localhost:9001` | `localhost` |
| `x-forwarded-for` | なし | `172.22.0.1` |
| `/api/a/whoami` を送ったとき | not found | `/whoami` に変換されて 200 |
| 偽の `X-Forwarded-For: 1.2.3.4` を付けたとき | `1.2.3.4` | `1.2.3.4, 172.22.0.1` |

---

## 4. 考察

### 4.1 CORS は「ブラウザが返事を渡さない」仕組み

Step 1 で、ブラウザは読み込みを拒否したが、api-a のログには 200 が記録されていた。リクエストはアプリに届き、アプリは返事を返している。止めているのはブラウザで、返事を JS に渡すことを拒否している。

Step 2 で nginx を入口にすると、ブラウザは `sec-fetch-site: same-origin` と判断し、エラーは消えた。同一オリジンかどうかを判定するブラウザに見えている住所は `http://localhost` だけで、裏の api-a の3000番は見えていない。別オリジンへのアクセスはなくなったのではなく、nginx → api-a というサーバ同士の通信に移っただけ。これは時代①の「住所と機械の切り離し」の効用である。

### 4.2 「直接」のつもりでも、L4 のプロキシを1枚通っていた

Step 1 で自分の PC から `localhost` に直接つないだのに、`remote-addr` は `127.0.0.1` ではなく `172.22.0.1` だった。Docker の `ports: "3001:3000"` は、ホスト側で待ち受ける中継役（`docker-proxy`）を用意し、中継役がコンテナへの接続を張り直している。アプリに見えるのは、自分に直接つながってきた接続の相手（Docker の内部ネットワークのゲートウェイ）である。

`host` が `localhost:3001` のままで、`X-Forwarded-*` がなかったことから、この中継役は中身を読まない L4 のプロキシだとわかる。

### 4.3 アプリから見える情報の変化と、その補い方

nginx を挟むと、アプリから見える情報は次のように変わった。

| 情報 | アプリから直接見える値 | 補う手段 | 補えなかったもの |
|---|---|---|---|
| 接続元 | nginx の IP（`172.22.0.4`） | `X-Forwarded-For` | ブラウザの本当の IP。手前の L4（Docker）の時点で失われ、nginx にも `172.22.0.1` しか見えていなかった |
| ホスト名 | nginx が設定した値 | `proxy_set_header Host $host` で引き継ぐ | なし |
| スキーム | `http`（nginx → api-a の接続） | `X-Forwarded-Proto` | なし |
| パス | 前置きを取った `/whoami` | （`X-Forwarded-Prefix` などがあるが、今回は未使用） | `/api/a` という前置き |

`X-Forwarded-For` に書けるのは、そのプロキシが知っている情報だけである。手前に L4 のプロキシがいると、そこで失われたものは後ろの L7 では取り戻せない。本番で ALB や CDN が前にいる構成では、それぞれのプロキシが何を引き継いでいるかを確認する必要がある。

### 4.4 リダイレクトループの原因は「情報は届いていたのに、使っていなかった」こと

Step 3-1 で、`x-forwarded-proto: https` はアプリまで届いていた。それでもアプリは自分への接続のスキーム（`http`）だけを見ていたので、`FORCE_HTTPS` を有効にすると、https で来るたびに「https へ行け」と返し続けた（3-2）。`TRUST_PROXY` でヘッダーを信じさせると直った（3-4）。

3-3 では、ループとは別の壊れ方が起きた。`Location` から `/api/a` が消え、ブラウザで開くと nginx の `location /` に当たってフロントの `index.html` が返る。エラーにならずに違うページが出るので、ループより気づきにくい。アプリは前置きを取られた後のパスしか知らないためである。

この2つの壊れ方から、「http なら https へ」は、TLS を終端していて元のパスも知っている nginx で行う方がよい（`return 302 https://$host$request_uri;`）。TLS に関わることは、TLS を終端する場所でまとめて扱う。自宅サーバでは、Cloudflare の「Always Use HTTPS」がこの役割にあたる。

### 4.5 L4 と L7 の違い

Step 4 で、L4 は L7 と同じく接続を張り直す（`remote-addr` は nginx）が、ヘッダーもパスも一切書き換えなかった。行き先は接続を受けた瞬間に、ポート番号だけで決まる。パスで振り分けられないのは、L4 が行き先を決める時点でまだ HTTP のリクエストを受け取っていないからである（事前チェック Q2）。

偽の `X-Forwarded-For` の結果は、セキュリティ上重要である。

- L4 は偽の値をそのまま通した。中身を読まないので、偽装を見抜くことも消すこともできない
- L7 は偽の値を消さず、右に追記しただけだった

どちらの経路でも、アプリが左端を本当のクライアント IP として読むとだまされる。信じてよいのは自分のプロキシが追記した右側だけで、「右から辿る」必要がある（Day 7 で `real_ip` を実装する）。

### 4.6 その他の気づき

- **「`localhost` の80番」という住所だけでは、誰が返事をするかは決まらない**：Windows のブラウザは Docker の nginx に、WSL の curl は WSL の中の Apache につながっていた。エラーの形（`Server` ヘッダー、`instance` の有無）と、待ち受けているプロセス（`ss -tlnp`）から、誰が返したかを切り分けられた
- **`Host` はただのヘッダー**：curl で1行書き換えるだけで、同じ IP・ポート・パスでも別のサイトとして扱われた
- **Cookie はホスト名単位で送られる**：ポートが違う別アプリの Cookie も届き、nginx はそれをそのまま裏に転送した。1つのドメインに複数のアプリをまとめると、あるアプリの Cookie が別のアプリにも届く（Day 8 に関係）
- **nginx は起動時に名前を IP に変換して覚える**：コンテナを作り直すと IP が変わるので、nginx の再起動が必要だった（時代④の「住所が絶えず変わる」問題の小さな実例）

### 4.7 自宅サーバ（Cloudflare）との対応

| 今日の構成 | 自宅サーバ |
|---|---|
| nginx の TLS 終端 | Cloudflare のエッジ |
| nginx の `server_name` でのホスト名振り分け | `cloudflared` の `ingress` ルール（`hostname` → `service`） |
| `X-Forwarded-For` | `CF-Connecting-IP` と `X-Forwarded-For` |
| アプリから見た接続元 = nginx | アプリから見た接続元 = `cloudflared`（`127.0.0.1`） |
| http → https のリダイレクトは入口で | Cloudflare の「Always Use HTTPS」 |

---

## 5. 結論

1. CORS エラーは、ブラウザが別オリジンの返事を JS に渡さない仕組みである。リバースプロキシで入口を1つにすると、ブラウザから見て同一オリジンになり、CORS 自体が不要になる
2. プロキシを挟むと、アプリから見える接続元はプロキシに、スキームはプロキシ→アプリの接続のものに変わる。失われた情報はヘッダーで補えるが、補えるのはそのプロキシが知っている情報だけである
3. TLS 終端の後ろのアプリが元のスキームを無視すると、リダイレクトループが起きる。前置きを取る構成では、アプリが作る URL も壊れる。http → https のリダイレクトは、TLS を終端する入口で行う方がよい
4. L4 は中身を読まずに流すので、パスで振り分けられず、ヘッダーも付け足せない。L7 は中身を読んで書き換えられるが、偽のヘッダーを自動では消さない。どちらの場合も、`X-Forwarded-For` は右から辿って、信頼できるプロキシが付けた部分だけを信じる必要がある
