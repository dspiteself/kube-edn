(ns kube-edn.config
  "Configuration defaults and resolution for kube-edn.

  A config map describes where EDN sources live, where rendered YAML goes, and
  which paths to skip during EDN->YAML conversion:

    {:edn-dir    \"edn\"      ; root of the EDN source tree
     :out-dir    \"output\"   ; root of the rendered YAML tree
     :exclusions [\"/secrets/\" \"/config-files/\"]}

  Every public kube-edn function takes such a map as its first argument, after
  being passed through `resolve-config` to fill in defaults.")

(def defaults
  "Default configuration, merged under any user-supplied config."
  {:edn-dir    "edn"
   :out-dir    "output"
   ;; Substring matches against the full path string. EDN files whose path
   ;; contains any of these are copied verbatim instead of converted to YAML
   ;; (they are literal payloads referenced by configMapGenerator/secretGenerator
   ;; or encrypted secret bundles, not Kubernetes manifests).
   :exclusions ["/secrets/" "/config-files/"]})

(defn resolve-config
  "Merge user config over `defaults`. Accepts nil. Returns a complete config map."
  [user]
  (merge defaults user))
