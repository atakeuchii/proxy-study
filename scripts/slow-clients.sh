#!/usr/bin/env bash
# 遅いクライアントを並列で走らせ続ける。
#
# 使い方: slow-clients.sh <並列数> <受信速度> <URL> [続ける秒数]
# 例:     slow-clients.sh 4 256k "http://api-a:3000/big?kb=4096" 60
#
# 各クライアントは、受信速度を制限した curl で URL をダウンロードし、終わったらまた最初から繰り返す。
set -u
n=$1
rate=$2
url=$3
duration=${4:-60}
end=$(( $(date +%s) + duration ))

for i in $(seq 1 "$n"); do
  (
    while [ "$(date +%s)" -lt "$end" ]; do
      curl -s -o /dev/null --limit-rate "$rate" \
        -w "[slow$i] $(date +%T) 開始 → status=%{http_code} 所要=%{time_total}s\n" "$url"
    done
  ) &
done
wait
echo "slow-clients: 終了"
