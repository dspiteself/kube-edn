# kube-edn

A small [babashka](https://babashka.org) library for GitOps repositories that
author Kubernetes manifests as **EDN**, render them to YAML, and drive them
through **kustomize / kubectl**.

You write manifests as plain Clojure data (EDN), keep them under version control,
and `kube-edn` gives your repo `render`, `build`, `diff`, and `apply` tasks plus
scaffolding to lay out a kustomize directory structure.

Why EDN instead of YAML?

- Real data structures — comments (`;;`), `#_` to disable a block, no
  significant whitespace or YAML foot-guns.
- One source of truth: EDN is rendered 1:1 to YAML; kustomize and kubectl
  consume the generated YAML exactly as if you'd hand-written it.

## Requirements

- `bb` (babashka) — `clj-yaml`, `babashka.fs`, `babashka.process` and
  `babashka.cli` all ship with babashka, so this library has **no external
  dependencies**.
- `kubectl` (v1.14+) for `build` / `diff` / `apply` (kustomize is built in).

## Add it to your repo

Reference the library as a git dependency in your repo's `bb.edn` and wire the
tasks:

```clojure
{:deps {io.github.dspiteself/kube-edn
        {:git/url "https://github.com/dspiteself/kube-edn"
         :git/tag "v0.1.0"
         :git/sha "e281374"}}
 :tasks
 {:requires ([kube-edn.api :as kube] [babashka.cli :as cli])

  :init (def cfg {:edn-dir "edn" :out-dir "output"})

  render            {:task (kube/render cfg (cli/parse-opts *command-line-args*))}
  build             {:task (kube/build cfg (cli/parse-opts *command-line-args*))}
  diff              {:task (kube/diff  cfg (cli/parse-opts *command-line-args*))}
  apply             {:task (kube/apply cfg (cli/parse-opts *command-line-args*))}
  scaffold          {:task (kube/scaffold-service cfg (cli/parse-opts *command-line-args*))}
  gen-kustomization {:task (kube/gen-kustomization cfg (cli/parse-opts *command-line-args*))}}}
```

For local development of the library itself, swap the dep for a path override:

```clojure
{:deps {io.github.dspiteself/kube-edn {:local/root "../kube-edn"}}}
```

(See [`example/`](example/) for a complete, runnable consuming repo.)

## Usage

```bash
bb render                              # edn/ -> output/ (YAML)
bb render --clean                      # wipe output/ first, then render
bb build  --overlay production         # render + kubectl kustomize output/production
bb diff   --overlay production         # render + kubectl diff -k  output/production
bb apply  --overlay production         # render + kubectl apply -k output/production
bb apply  --overlay production --context staging --dry-run client
```

`--overlay` is a path **relative to `output/`** — i.e. it mirrors the structure
of your `edn/` tree. `build`/`diff`/`apply` **render first by default**, so the
YAML you operate on is never stale. Pass `--no-render` to skip rendering when
`output/` is already fresh:

```bash
bb diff --overlay production --no-render   # operate on existing output/, don't re-render
```

`apply`/`diff` also accept `--context`, `--namespace`, and `--dry-run`.

## Directory layout

`kube-edn` follows the standard kustomize **base + overlays** pattern. EDN lives
under `edn/`; rendered YAML mirrors it under `output/` (gitignored):

```
edn/
├── base/
│   ├── kustomization.edn                 ; lists ./services/<name> etc.
│   └── services/
│       └── hello/
│           ├── kustomization.edn         ; lists ./deployment/hello.yaml ...
│           ├── deployment/hello.edn
│           └── service/hello.edn
└── production/
    └── kustomization.edn                 ; {:resources ["../base"] :namespace "production"}
```

Each manifest is one EDN file. `kustomization.edn` files are themselves rendered
to `kustomization.yaml`, and their `:resources` reference the **`.yaml`** names
(what kustomize will see after rendering).

### Rendering rules

- Every `.edn` file becomes `.yaml` (block style) at the mirrored path.
- Files whose path contains `/secrets/` or `/config-files/` (configurable via
  `:exclusions`) are **copied verbatim** instead of converted — they are literal
  payloads (referenced by `configMapGenerator`/`secretGenerator`) or encrypted
  bundles, not manifests.
- Any non-`.edn` file (e.g. `logback.xml`, `zoo.cfg`) is copied verbatim so
  generators can resolve their referenced files.
- Rendering is non-destructive by default (stale YAML is not deleted); use
  `--clean` for a fresh rebuild.

## Scaffolding

```bash
# Bootstrap a fresh repo: edn/base + overlays + .gitignore for output/
bb -e '(require (quote kube-edn.api)) (kube-edn.api/init {} {:overlays ["production"]})'

# Create a new service with kind subdirectories + base/overlay kustomizations
bb scaffold --name billing --group services --overlay production

# Regenerate a kustomization.edn resource list from files on disk
bb gen-kustomization --dir base/services/billing --recursive
```

`scaffold` creates `deployment/`, `service/`, `configmap/`, `serviceAccount/`
kind directories by default (override with `:kinds`), a base `kustomization.edn`
with an empty `:resources` list, and an overlay `kustomization.edn` that
references `../../../base/<group>/<name>` and sets `:namespace`. Drop your
manifests into the kind directories, then run `gen-kustomization` to fill in the
resource list. Scaffolding never overwrites existing files unless `--force`.

## Configuration

The config map (first argument to every function) supports:

| key           | default                          | meaning                                  |
| ------------- | -------------------------------- | ---------------------------------------- |
| `:edn-dir`    | `"edn"`                          | root of the EDN source tree              |
| `:out-dir`    | `"output"`                       | root of the rendered YAML tree           |
| `:exclusions` | `["/secrets/" "/config-files/"]` | path substrings copied verbatim, not rendered |

## API

`kube-edn.api` re-exports everything:

- `(render config)` / `(render config {:clean true})`
- `(build config opts)` — `opts` is `{:overlay "..."}` (or a bare overlay string)
- `(diff config opts)` — exit code 1 (differences found) is treated as success
- `(apply config opts)` — honors `:context` / `:namespace` / `:dry-run`

`build`/`diff`/`apply` render first by default; pass `{:render false}` (the
`--no-render` flag) to skip it.
- `(gen-kustomization config {:dir "..." :recursive? true})`
- `(scaffold-service config {:name "..." :group "services" :overlay "production"})`
- `(init config {:overlays ["production"]})`

## Status

v1 focuses on render / build / diff / apply and scaffolding. Secret encryption
(e.g. GCP KMS) is intentionally out of scope for now; the `/secrets/` path
exclusion is already in place so encrypted bundles render correctly when that is
added later.
