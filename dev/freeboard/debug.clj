(ns freeboard.debug
  "Live debug harness for the freeboard app, built on browser-use-clj's
   Playwright session (`browseruse.playwright-browser`). Drives a real Chromium
   against the served app, captures console (errors/warnings), probes
   `window.freeboard.web/debug-state`, exercises add-sticky, re-probes,
   screenshots, and prints a structured root-cause diagnosis — so 'it displayed
   but doesn't work' becomes concrete (e.g. kami GPU host / WebGPU missing →
   renderer never ran while board state still mutates). See ADR-2606280200.

   Run (after `bb serve`):
     clojure -A:debug -M -e \"(require 'freeboard.debug)(println (:diagnosis (freeboard.debug/diagnose! {})))\"
   First time: `npx playwright install chromium` (Playwright Chromium binary)."
  (:require [browseruse.playwright-browser :as pw]
            [cheshire.core :as json]))

(def ^:private default-url "http://localhost:8200/")

(defn- probe [page]
  ;; debug-state → JSON string → clj (java->clj in pw is private, so go via JSON)
  (-> (.evaluate page "() => JSON.stringify((window.freeboard && window.freeboard.web && window.freeboard.web.debug_state)
                          ? window.freeboard.web.debug_state() : {error:'freeboard.web not loaded'})")
      str (json/parse-string true)))

(defn- click-add-sticky [page]
  ;; call the API directly (the toolbar button is now a tool-mode toggle)
  (.evaluate page "() => { const w=window.freeboard && window.freeboard.web;
                           if(w && w.add_sticky){ w.add_sticky(640,360); return true; } return false; }"))

(def ^:private hints
  {:bundle-load-error        "'goog is not defined' — a shadow-cljs DEV (:compile) bundle was loaded via ESM import(); the goog module-loader can't bootstrap that way. Build the :advanced RELEASE (single self-contained file): scripts/build.sh / `clojure -M:shadow ... release app`."
   :app-not-loaded           "window.freeboard.web missing — freeboard.js didn't load. Check the <script>/import in index.html and that public/js/freeboard.js exists."
   :no-webgpu                "navigator.gpu absent — launch Chromium with --enable-unsafe-webgpu --enable-features=Vulkan, or a WebGPU browser."
   :kami-host-wasm-missing   "window.KamiCljHost undefined — public/wasm not built. Run scripts/build.sh (wasm-pack kami-clj-host); use --dev for a fast host build."
   :host-boot-failed         "KamiCljHost present but backend nil — KamiCljHost.create rejected (adapter/device). See :errors."
   :renderer-never-presented "backend bound but 0 frames — present!/submit! not firing. See :errors."
   :input-not-wired          "GPU ok but add-sticky didn't grow board state — input→ops wiring broken."
   :gpu-host-panic           "kami-clj-host (Rust/wgpu) panicked during submit_frame — the canvas stays blank/black though the JS pipeline fired. See :errors for the wasm stack (e.g. Buffer::slice / check_buffer_bounds = a 0-size buffer). Fix in kami-clj-host + rebuild the wasm."
   :ok                       "board state mutates + GPU host bound + frames presented — app is live."})

(defn- classify [before after errors]
  (cond (some #(re-find #"goog is not defined" (str %)) errors) :bundle-load-error
        (some #(re-find #"unreachable|rust_panic|wgpu::|RuntimeError" (str %)) errors) :gpu-host-panic
        (:error before)                     :app-not-loaded
        (not (:webgpu after))               :no-webgpu
        (not (:kamiHost after))             :kami-host-wasm-missing
        (not (:backend after))              :host-boot-failed
        (zero? (or (:frame after) 0))       :renderer-never-presented
        (= (:items before) (:items after))  :input-not-wired
        :else                               :ok))

(defn diagnose!
  "Open `:url`, capture console, probe health, exercise add-sticky, re-probe,
   screenshot to `:shot`, and return + print a diagnosis map."
  [{:keys [url shot headless?] :or {url default-url shot "/tmp/freeboard-debug.png" headless? false}}]
  (let [logs (atom [])
        {:keys [browser page screenshot close]} (pw/playwright-session url {:headless? headless?})]
    (try
      (.onConsoleMessage page (reify java.util.function.Consumer
                                (accept [_ m] (swap! logs conj {:type (.type ^com.microsoft.playwright.ConsoleMessage m)
                                                                :text (.text ^com.microsoft.playwright.ConsoleMessage m)}))))
      (.onPageError page (reify java.util.function.Consumer
                           (accept [_ e] (swap! logs conj {:type "pageerror" :text (str e)}))))
      (.navigate page url)                                     ; raw Playwright Page.navigate
      (.waitForLoadState page)
      (Thread/sleep 2500)                                      ; wasm host + boot settle
      (let [before (probe page)
            _      (click-add-sticky page)
            _      (Thread/sleep 600)
            after  (probe page)
            errs   (filterv #(#{"error" "pageerror"} (:type %)) @logs)
            warns  (filterv #(= "warning" (:type %)) @logs)
            _      (when screenshot (screenshot shot))
            diag   (classify before after (map :text errs))
            report {:diagnosis diag :hint (hints diag)
                    :before before :after after
                    :added-item? (> (or (:items after) 0) (or (:items before) 0))
                    :errors (mapv :text errs) :warnings (mapv :text warns)
                    :debug-logs (->> @logs (map :text) (filter #(re-find #"kami-dbg" (str %))) vec)
                    :screenshot shot}]
        (println "\n===== freeboard diagnosis =====")
        (doseq [[k v] [["diagnosis" (name diag)] ["hint" (:hint report)]
                       ["before" before] ["after" after]
                       ["errors" (:errors report)] ["screenshot" shot]]]
          (println (format "%-10s: %s" k v)))
        report)
      (finally (close)))))
