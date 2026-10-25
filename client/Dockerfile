# 負荷をかける側のコンテナ。curl、jq、oha(負荷ツール)が入っている。
FROM alpine:edge
RUN apk add --no-cache bash curl jq coreutils \
 && apk add --no-cache oha --repository=https://dl-cdn.alpinelinux.org/alpine/edge/testing
