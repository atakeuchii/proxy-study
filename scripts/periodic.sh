#!/usr/bin/env bash
# 3件に1件だけ重いリクエストを送る(事前チェック Q3 の状況)。
#
# 使い方: periodic.sh <URLの土台> [件数] [重い処理のミリ秒]
# 例:     periodic.sh http://proxy:8003 60 3000
#
# 軽いリクエスト(10ms)は返事を待ってから次へ進み、重いリクエストは返事を待たずに次へ進む。
# どのインスタンスが返事をしたかは、nginx が付ける X-Upstream ヘッダーで表示する。
set -u
base=$1
n=${2:-60}
heavy_ms=${3:-3000}

for i in $(seq 1 "$n"); do
  if [ $((i % 3)) -eq 0 ]; then
    curl -s -o /dev/null -D - "$base/slow?ms=$heavy_ms" -w "[%{time_total}s]\n" \
      | awk -v i="$i" '/^X-Upstream/ {up=$2} /^\[/ {printf "#%-3d heavy  up=%s  所要=%s\n", i, up, $0}' &
  else
    curl -s -o /dev/null -D - "$base/slow?ms=10" -w "[%{time_total}s]\n" \
      | awk -v i="$i" '/^X-Upstream/ {up=$2} /^\[/ {printf "#%-3d light  up=%s  所要=%s\n", i, up, $0}'
  fi
  sleep 0.2
done
wait
