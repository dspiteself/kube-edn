(ns kube-edn.api
  "Public facade for kube-edn. Require this single namespace from a consuming
  bb.edn and wire its functions to tasks.

  All functions take a config map first (see kube-edn.config). Operations that
  target an overlay take an opts map second — typically the result of
  (babashka.cli/parse-opts *command-line-args*), e.g. {:overlay \"production\"}."
  (:refer-clojure :exclude [apply])
  (:require [kube-edn.kustomize :as kustomize]
            [kube-edn.render :as render]
            [kube-edn.scaffold :as scaffold]))

(defn render
  "Render the EDN tree to YAML. opts (optional): {:clean bool}."
  ([config] (render/render config))
  ([config opts] (render/render config opts)))

(defn build
  "Render, then `kubectl kustomize <out-dir>/<overlay>`."
  [config opts] (kustomize/build config opts))

(defn diff
  "Render, then `kubectl diff -k <out-dir>/<overlay>`."
  [config opts] (kustomize/diff config opts))

(defn apply
  "Render, then `kubectl apply -k <out-dir>/<overlay>`."
  [config opts] (kustomize/apply config opts))

(defn gen-kustomization
  "(Re)generate a kustomization.edn under <edn-dir>/<dir>."
  [config opts] (scaffold/gen-kustomization config opts))

(defn scaffold-service
  "Scaffold base + overlay directories for a new service."
  [config opts] (scaffold/scaffold-service config opts))

(defn init
  "Bootstrap a fresh GitOps repo layout."
  [config opts] (scaffold/init config opts))
