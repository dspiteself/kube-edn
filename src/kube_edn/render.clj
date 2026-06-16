(ns kube-edn.render
  "Render an EDN source tree into a mirrored YAML tree.

  Generalizes breeze-deploy's renderjson.clj: walk the EDN root recursively and
  reproduce its structure under the output root, converting every `.edn`
  Kubernetes manifest (including `kustomization.edn`) to `.yaml`, and copying
  every other file verbatim so kustomize generators can still find their
  referenced payloads (logback.xml, zoo.cfg, encrypted secret bundles, ...)."
  (:require [babashka.fs :as fs]
            [clj-yaml.core :as yaml]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [kube-edn.config :as config]))

(defn- excluded?
  "True when `path-str` contains any of the configured exclusion substrings."
  [exclusions path-str]
  (boolean (some #(str/includes? path-str %) exclusions)))

(defn- edn->yaml
  "Read an EDN manifest file and return its YAML string (block style)."
  [file]
  (-> (slurp (fs/file file))
      (edn/read-string)
      (yaml/generate-string :dumper-options {:flow-style :block})))

(defn- yaml-target
  "Given an output path ending in `.edn`, return the same path with `.yaml`."
  [target]
  (str (fs/strip-ext (str target)) ".yaml"))

(defn render
  "Render the EDN tree at (:edn-dir config) into (:out-dir config).

  For each entry under the EDN root:
    - directories are recreated under the output root
    - `.edn` files whose path matches none of (:exclusions config) are converted
      to YAML and written with a `.yaml` extension
    - everything else (non-edn files, or excluded `.edn` payloads) is copied
      verbatim with :replace-existing

  Options:
    :clean  when true, delete the output root before rendering (default false;
            note: without :clean stale outputs are not removed).

  Returns {:rendered n :copied m :out-dir <str>}."
  ([config] (render config nil))
  ([config {:keys [clean]}]
   (let [{:keys [edn-dir out-dir exclusions]} (config/resolve-config config)
         edn-root (fs/path edn-dir)
         out-root (fs/path out-dir)]
     (when-not (fs/exists? edn-root)
       (throw (ex-info (str "EDN source directory does not exist: " edn-dir)
                       {:edn-dir edn-dir})))
     (when clean (fs/delete-tree out-root))
     (fs/create-dirs out-root)
     (let [counts (atom {:rendered 0 :copied 0})]
       (doseq [p (fs/glob edn-dir "**" {:recursive true})]
         (let [rel    (.relativize edn-root p)
               target (fs/path out-root rel)]
           (cond
             (fs/directory? p)
             (fs/create-dirs target)

             (and (str/ends-with? (str (fs/file-name p)) ".edn")
                  (not (excluded? exclusions (str p))))
             (do (fs/create-dirs (fs/parent target))
                 (spit (yaml-target target) (edn->yaml p))
                 (swap! counts update :rendered inc))

             :else
             (do (fs/create-dirs (fs/parent target))
                 (fs/copy p target {:replace-existing true})
                 (swap! counts update :copied inc)))))
       (let [{:keys [rendered copied]} @counts]
         (println (format "kube-edn: rendered %d EDN -> YAML, copied %d file(s) into %s"
                          rendered copied out-dir))
         (assoc @counts :out-dir out-dir))))))
