(ns kube-edn.kustomize
  "kubectl/kustomize wrappers: build, diff and apply an overlay.

  Each operation renders the EDN tree to YAML first, then runs kubectl against
  the rendered overlay directory under (:out-dir config). kubectl output streams
  straight to the terminal so colored diffs and interactive context/confirmation
  prompts work."
  (:refer-clojure :exclude [apply])
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [kube-edn.config :as config]
            [kube-edn.render :as render]))

(defn- overlay-str
  "Coerce opts into the overlay path string. Accepts a bare string or a map with
  :overlay. Throws a friendly error when missing."
  [opts]
  (let [o (if (map? opts) (:overlay opts) opts)]
    (when (or (nil? o) (and (string? o) (clojure.core/empty? o)))
      (throw (ex-info "No overlay specified. Pass --overlay <path-under-out-dir>."
                      {:opts opts})))
    (str o)))

(defn overlay-path
  "Resolve the rendered overlay directory: (:out-dir config)/<overlay>."
  [config opts]
  (str (fs/path (:out-dir config) (overlay-str opts))))

(defn- kubectl-flags
  "Translate recognized opts into kubectl flag vectors."
  [opts]
  (when (map? opts)
    (cond-> []
      (:context opts)   (conj "--context" (str (:context opts)))
      (:namespace opts) (conj "--namespace" (str (:namespace opts)))
      (:dry-run opts)   (conj (str "--dry-run="
                                   (if (true? (:dry-run opts)) "client" (:dry-run opts)))))))

(defn- run!
  "Run kubectl with inherited IO. Returns the completed process. Does not throw
  on non-zero exit (callers decide)."
  [args]
  @(p/process (into ["kubectl"] args) {:inherit true}))

(defn- maybe-render
  "Render the EDN tree before a kubectl operation. Rendering is the default; skip
  it (when output/ is already fresh) by passing `--no-render`, which babashka.cli
  parses to {:render false} (also accepts {:no-render true})."
  [cfg opts]
  (when-not (and (map? opts)
                 (or (false? (:render opts)) (:no-render opts)))
    (render/render cfg)))

(defn build
  "Render (by default), then `kubectl kustomize <out-dir>/<overlay>` — prints the
  combined manifest. Pass {:no-render true} to skip rendering. Throws on kubectl
  failure."
  [config opts]
  (let [cfg (config/resolve-config config)]
    (maybe-render cfg opts)
    (let [{:keys [exit] :as res} (run! ["kustomize" (overlay-path cfg opts)])]
      (when-not (zero? exit)
        (throw (ex-info "kubectl kustomize failed" {:exit exit})))
      res)))

(defn diff
  "Render (by default), then `kubectl diff -k <out-dir>/<overlay>`. Pass
  {:no-render true} to skip rendering.

  kubectl diff exits 1 when differences exist (not an error) and >1 on a real
  failure, so exit 0 and 1 are both treated as success."
  [config opts]
  (let [cfg (config/resolve-config config)]
    (maybe-render cfg opts)
    (let [{:keys [exit] :as res} (run! (into ["diff" "-k" (overlay-path cfg opts)]
                                             (kubectl-flags opts)))]
      (when (> exit 1)
        (throw (ex-info "kubectl diff failed" {:exit exit})))
      res)))

(defn apply
  "Render (by default), then `kubectl apply -k <out-dir>/<overlay>`. Pass
  {:no-render true} to skip rendering. Honors :context, :namespace and :dry-run
  opts. Throws on kubectl failure."
  [config opts]
  (let [cfg (config/resolve-config config)]
    (maybe-render cfg opts)
    (let [{:keys [exit] :as res} (run! (into ["apply" "-k" (overlay-path cfg opts)]
                                             (kubectl-flags opts)))]
      (when-not (zero? exit)
        (throw (ex-info "kubectl apply failed" {:exit exit})))
      res)))
