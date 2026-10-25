(defproject proxy-study "0.1.0-SNAPSHOT"
  :description "リバースプロキシ実践編の共通バックエンド"
  :url "https://github.com/atakeuchii/proxy-study"
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [ring/ring-core "1.12.2"]
                 [ring/ring-jetty-adapter "1.12.2"]
                 [cheshire "5.13.0"]
                 [com.taoensso/carmine "3.5.0"]]
  :main proxy-study.server
  :uberjar-name "proxy-study-standalone.jar"
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}}
  :repl-options {:init-ns proxy-study.server})
