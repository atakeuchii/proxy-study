# ---- ビルド用のステージ: Leiningen で uberjar を作る ----
FROM clojure:temurin-21-lein AS build
WORKDIR /app
COPY project.clj .
RUN lein deps
COPY src src
RUN lein uberjar

# ---- 実行用のステージ: JRE だけの軽いイメージ ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/proxy-study-standalone.jar app.jar
EXPOSE 3000
CMD ["java", "-jar", "app.jar"]
