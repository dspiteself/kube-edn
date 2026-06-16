(ns kube-edn.scaffold
  "Scaffolding helpers: bootstrap a GitOps repo, create a new service with the
  conventional base/overlay + kind-subdirectory layout, and (re)generate a
  kustomization.edn resource list from the EDN files on disk.

  Layout convention (mirrors breeze-deploy):

    <edn-dir>/base/<group>/<name>/<kind>/<resource>.edn
    <edn-dir>/base/<group>/<name>/kustomization.edn        ; lists resource yamls
    <edn-dir>/<overlay>/<group>/<name>/kustomization.edn   ; refs ../base, sets ns

  where <group> is usually \"services\" or \"cronjobs\" and <kind> is a Kubernetes
  kind directory like deployment/, service/, configmap/, serviceAccount/."
  (:require [babashka.fs :as fs]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [kube-edn.config :as config]))

(def kustomization-header
  {:apiVersion "kustomize.config.k8s.io/v1beta1"
   :kind       "Kustomization"})

(defn- spit-edn
  "Pretty-print `data` as EDN to `file`. Skips existing files unless force?."
  [file data {:keys [force?]}]
  (cond
    (and (fs/exists? file) (not force?))
    (println (str "kube-edn: skip (exists) " file))
    :else
    (do (fs/create-dirs (fs/parent file))
        (spit (fs/file file) (with-out-str (pp/pprint data)))
        (println (str "kube-edn: wrote " file)))))

(defn gen-kustomization
  "(Re)generate a kustomization.edn under <edn-dir>/<dir>.

  Globs `.edn` files (recursively when :recursive? is set), excluding any
  kustomization.edn and configured :exclusions, and writes
  {:apiVersion ... :kind \"Kustomization\" :resources [\"./a.yaml\" ...]} pointing
  at the rendered YAML names (relative to that directory).

  Boolean opts accept either the `?`-suffixed key or the bare CLI flag key
  (e.g. both :recursive? and --recursive / :recursive).

  opts: {:dir <rel-under-edn-dir> :recursive? bool :force? bool}."
  [config {:keys [dir] :as opts}]
  (let [recursive? (or (:recursive? opts) (:recursive opts))
        {:keys [edn-dir exclusions]} (config/resolve-config config)
        _      (when (str/blank? (str dir))
                 (throw (ex-info "gen-kustomization needs --dir <path-under-edn-dir>" {})))
        d      (fs/path edn-dir dir)
        _      (when-not (fs/exists? d)
                 (throw (ex-info (str "Directory does not exist: " d) {:dir (str d)})))
        glob   (if recursive? "**.edn" "*.edn")
        files  (->> (fs/glob d glob)
                    (remove fs/directory?)
                    (remove #(= "kustomization.edn" (str (fs/file-name %))))
                    (remove #(some (fn [ex] (str/includes? (str %) ex)) exclusions))
                    sort)
        resources (mapv (fn [f]
                          (let [rel (str (.relativize (fs/path d) f))]
                            (str "./" (str/replace rel #"\.edn$" ".yaml"))))
                        files)]
    (spit-edn (fs/path d "kustomization.edn")
              (assoc kustomization-header :resources resources)
              {:force? true})
    {:dir (str d) :resources resources}))

(defn scaffold-service
  "Create the conventional layout for a new service.

  Creates <edn-dir>/base/<group>/<name>/<kind>/ directories with a base
  kustomization.edn (empty :resources, fill via gen-kustomization once manifests
  are added), and an overlay kustomization.edn at
  <edn-dir>/<overlay>/<group>/<name>/ that references ../base and sets the
  namespace.

  opts: {:name s (required)
         :group \"services\"
         :overlay \"production\"
         :kinds [\"deployment\" \"service\" \"configmap\" \"serviceAccount\"]
         :force? bool}  ; :force? also accepts the --force / :force CLI flag"
  [config {:keys [name group overlay kinds] :as opts}]
  (let [force? (or (:force? opts) (:force opts))
        {:keys [edn-dir]} (config/resolve-config config)
        _       (when (str/blank? (str name))
                  (throw (ex-info "scaffold-service needs --name <service>" {})))
        group   (or group "services")
        overlay (or overlay "production")
        kinds   (or kinds ["deployment" "service" "configmap" "serviceAccount"])
        base    (fs/path edn-dir "base" group name)
        ovl     (fs/path edn-dir overlay group name)]
    (doseq [k kinds]
      (fs/create-dirs (fs/path base k)))
    (spit-edn (fs/path base "kustomization.edn")
              (assoc kustomization-header :resources [])
              {:force? force?})
    ;; depth from <overlay>/<group>/<name> back up to base/<group>/<name>
    (let [base-ref (str (apply str (repeat 3 "../")) "base/" group "/" name)]
      (spit-edn (fs/path ovl "kustomization.edn")
                (assoc kustomization-header
                       :resources [base-ref]
                       :namespace overlay
                       :generatorOptions {:disableNameSuffixHash true})
                {:force? force?}))
    {:base (str base) :overlay (str ovl)}))

(defn init
  "Bootstrap a fresh GitOps repo: create <edn-dir>/base and each overlay with a
  top-level kustomization.edn (overlays reference ../base and set their
  namespace), and add <out-dir> to .gitignore.

  opts: {:overlays [\"production\"] :force? bool}  ; :force? also accepts --force / :force"
  [config {:keys [overlays] :as opts}]
  (let [force? (or (:force? opts) (:force opts))
        {:keys [edn-dir out-dir]} (config/resolve-config config)
        overlays (or overlays ["production"])]
    (fs/create-dirs (fs/path edn-dir "base"))
    (spit-edn (fs/path edn-dir "base" "kustomization.edn")
              (assoc kustomization-header :resources [])
              {:force? force?})
    (doseq [o overlays]
      (spit-edn (fs/path edn-dir o "kustomization.edn")
                (assoc kustomization-header
                       :resources ["../base"]
                       :namespace o)
                {:force? force?}))
    (let [gi      (fs/file ".gitignore")
          line    (str "/" out-dir "/")
          present (and (fs/exists? gi)
                       (some #(= line (str/trim %)) (str/split-lines (slurp gi))))]
      (when-not present
        (spit gi (str (when (fs/exists? gi) "\n") line "\n") :append true)
        (println (str "kube-edn: added " line " to .gitignore"))))
    {:edn-dir edn-dir :overlays overlays}))
