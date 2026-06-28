#!/usr/bin/env bash
# freeboard production build: kami-clj-host wasm (kami-render/wgpu) + cljs brain.
# Mirrors kami-app-sip-clj. Outputs public/wasm/kami_clj_host.js + public/js/freeboard.js.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
KE="$HERE/../kami-engine"

echo "==> [1/2] kami-clj-host wasm (wgpu host)"
if command -v wasm-pack >/dev/null; then
  wasm-pack build "$KE/kami-clj-host" --target web --out-dir "$HERE/public/wasm" -- --features host
else
  echo "!! wasm-pack not found — install: cargo install wasm-pack (skipping GPU host)"
fi

echo "==> [2/2] cljs brain (shadow-cljs release)"
(cd "$HERE" && clojure -M:shadow -m shadow.cljs.devtools.cli release app)

echo "==> done. serve:  (cd public && python3 -m http.server 8200)  →  http://localhost:8200"
