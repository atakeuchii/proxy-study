#!/usr/bin/env bash
# 各インスタンスの /stats(管理用ポート 9100)を1秒ごとに表示する。
#
# 使い方: watch-stats.sh [インスタンス名...]
# 例:     watch-stats.sh                 # api-a だけ
#         watch-stats.sh api-a api-b api-c
#
# 表示の意味:
#   busy    使用中の枠 / 枠の数
#   handler ハンドラの中にいる数(busy との差 = 返事の書き込みで拘束されている数)
#   queued  枠が空くのを待っている数
#   done    これまでに処理し終えた件数(パスごと)
hosts=${*:-api-a}

while true; do
  line="$(date +%T)"
  for h in $hosts; do
    s=$(curl -s --max-time 2 "http://$h:9100/stats" \
      | jq -r '"\(.instance): busy=\(."busy-workers")/\(.workers) handler=\(."in-handler") queued=\(.queued) done={\(.completed | to_entries | map("\(.key)=\(.value)") | join(","))}"' 2>/dev/null)
    line="$line | ${s:-$h: (応答なし)}"
  done
  echo "$line"
  sleep 1
done
