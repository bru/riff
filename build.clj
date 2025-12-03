(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'net.clojars.bru/riff)
(def version "0.1.0")
(def class-dir "target/classes")

(defn- jar-opts [opts]
  (assoc opts
         :lib lib
         :version version
         :jar-file (format "target/%s-%s.jar" (name lib) version)
         :basis (b/create-basis {})
         :class-dir class-dir
         :target "target"
         :src-dirs ["src"]
         :pom-data [[:description "A hierarchical keyboard shortcut management library for ClojureScript applications"]
                    [:url "https://github.com/bru/riff"]
                    [:licenses
                     [:license
                      [:name "Eclipse Public License 1.0"]
                      [:url "http://opensource.org/licenses/eclipse-1.0.php"]]]
                    [:developers
                     [:developer
                      [:name "bru"]]]
                    [:scm
                     [:url "https://github.com/bru/riff"]
                     [:connection "scm:git:git://github.com/bru/riff.git"]
                     [:developerConnection "scm:git:ssh://git@github.com/bru/riff.git"]
                     [:tag (str "v" version)]]]))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [opts]
  (clean nil)
  (let [opts (jar-opts opts)]
    (println "Building JAR:" (:jar-file opts))
    (b/write-pom opts)
    (b/copy-dir {:src-dirs ["src"]
                 :target-dir class-dir})
    (b/jar opts)
    (println "JAR built successfully:" (:jar-file opts))))

(defn deploy [opts]
  (let [{:keys [jar-file] :as opts} (jar-opts opts)]
    (jar opts)
    (println "Deploying to Clojars...")
    (dd/deploy {:installer :remote
                :artifact jar-file
                :pom-file (b/pom-path opts)})
    (println "Deployed successfully!")
    opts))

(defn ci [_]
  (clean nil)
  (jar nil)
  (println "CI build complete"))
