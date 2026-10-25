#!/usr/bin/env bash
# 同じ Cookie を持ち回って /session を何回か呼ぶ(1人のユーザーが続けてアクセスする状況)。
#
# 使い方: session.sh <URLの土台> [回数]
# 例:     session.sh http://proxy:8003 6
set -u
base=$1
n=${2:-6}
jar=$(mktemp)    # Cookie を保存するファイル(ブラウザの Cookie の代わり)

for i in $(seq 1 "$n"); do
  curl -s -b "$jar" -c "$jar" "$base/session" \
    | jq -c --arg i "$i" '{n: ($i|tonumber), instance, count, store: ."session-store"}'
done
rm -f "$jar"
