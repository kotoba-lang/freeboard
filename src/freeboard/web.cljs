(ns freeboard.web
  "Browser entry. Holds board state, turns pointer/wheel input into board ops,
   and submits one render-IR draw-list per frame to the kami-render (wgpu) host.
   The GPU work lives in kami-render via kami-engine-sdk-clj — this file is just
   the brain + input, exactly the kami-app-sip-clj split. See ADR-2606280200.

   NOTE: the kami wasm host attach (`submit-draws!`) binds to
   kami-engine-sdk-clj's browser backend; index.html boots it after wasm init."
  (:require [freeboard.board :as b]
            [freeboard.import :as imp]
            [freeboard.scene :as sc]
            [freeboard.render-ir :as rir]
            [freeboard.doc :as fdoc]
            [clojure.edn :as edn]
            [kami.ecs :as ecs]
            [kami.render :as kr]
            [kami.gpu :as gpu]
            [kami.backend.browser :as kb]))

(defonce app (atom {:board (b/new-board "Freeboard")
                    :drag  nil :frame 0 :w 1280 :h 720
                    :tool "select" :pending nil}))             ; tool ∈ select/sticky/shape/text/connector/pen

;; ---- kami host (kami-render/wgpu via kami-engine-sdk-clj) -------------------
(defonce ^:private backend (atom nil))

(defn- present!
  "Build the kami ECS world from the board, assemble one render-IR frame, and
   submit it (v2 packing = per-instance tint). The renderer is a dumb executor.
   Uses an orthographic screen-space camera sized to the canvas."
  []
  (when-let [be @backend]
    (let [{:keys [board w h frame]} @app
          snap  (sc/scene-snapshot board [w h])
          world (ecs/load-snapshot snap)                       ; 1-arg: snapshot → fresh world
          fr    (kr/frame world {:n frame :aspect (/ (double w) (max 1.0 (double h))) :clear sc/nintendo-cream})]
      (gpu/submit! be fr {:tint? true})
      (swap! app update :frame inc))))

;; ---- tools -----------------------------------------------------------------
(declare begin-edit)
(defn ^:export set-tool [t] (swap! app assoc :tool t :pending nil))

(defn- world-at [sx sy] (b/screen->world (get-in @app [:board :freeboard/viewport]) [sx sy]))

(defn- add-at [kind sx sy]
  (let [[wx wy] (world-at sx sy)
        id (b/gen-id)
        base (case kind
               :sticky {:item/w 180 :item/h 120 :item/fill "#ffeb8a" :text/runs [{:text ""}]}
               :shape  {:item/w 140 :item/h 100 :item/fill "#cfe0ff"}
               :text   {:item/w 200 :item/h 40  :item/fill "#ffffff" :text/runs [{:text ""}]})]
    (swap! app update :board b/add-item (merge {:item/id id :item/kind kind :item/x wx :item/y wy} base))
    id))

;; ---- input → ops -----------------------------------------------------------
;; NOTE: every fn invoked from index.html MUST carry ^:export, else the
;; :advanced release DCEs/munges it (dev :compile keeps names, hiding the bug —
;; surfaced by freeboard.debug: "TypeError: fb.add_sticky is not a function").
(defn ^:export on-pointer-down [sx sy]
  (let [{:keys [tool board pending]} @app
        hit (b/hit-test-screen board [sx sy])]
    (case tool
      "sticky" (add-at :sticky sx sy)
      "shape"  (add-at :shape sx sy)
      "text"   (begin-edit (add-at :text sx sy))
      "pen"    (let [[wx wy] (world-at sx sy) id (b/gen-id)]
                 (swap! app #(-> % (update :board b/add-item
                                           {:item/id id :item/kind :ink :ink/points [[wx wy]]
                                            :ink/width 3.0 :item/stroke "#222"
                                            :item/x wx :item/y wy :item/w 1 :item/h 1})
                                 (assoc :drag {:mode :ink :id id}))))
      "connector" (when hit
                    (if pending
                      (do (when (not= pending (:item/id hit))
                            (swap! app update :board b/add-connector pending (:item/id hit)))
                          (swap! app assoc :pending nil))
                      (swap! app assoc :pending (:item/id hit))))
      ;; select (default): move item under cursor, else pan
      (if hit
        (swap! app assoc :drag {:mode :move :id (:item/id hit) :last [sx sy]}
                         :board (b/bring-to-front board (:item/id hit)))
        (swap! app assoc :drag {:mode :pan :last [sx sy]}))))
  (present!))

(defn ^:export on-pointer-move [sx sy]
  (when-let [{:keys [mode id last]} (:drag @app)]
    (let [[lx ly] last dx (- sx lx) dy (- sy ly)
          z (get-in @app [:board :freeboard/viewport :zoom])]
      (swap! app (fn [s]
                   (-> s
                       (update :board (fn [bd]
                                        (case mode
                                          :pan  (b/pan bd dx dy)
                                          :move (b/move-item bd id (/ dx z) (/ dy z))
                                          :ink  (b/extend-ink bd id (world-at sx sy)))))
                       (cond-> last (assoc-in [:drag :last] [sx sy])))))
      (present!))))

(defn ^:export on-pointer-up [] (swap! app assoc :drag nil))

(defn ^:export on-wheel [sx sy delta]
  (let [z (get-in @app [:board :freeboard/viewport :zoom])
        nz (* z (if (neg? delta) 1.1 (/ 1.0 1.1)))]
    (swap! app update :board b/zoom-at nz [sx sy])
    (present!)))

;; ---- toolbar ops -----------------------------------------------------------
(defn ^:export add-sticky [sx sy]
  (let [[wx wy] (b/screen->world (get-in @app [:board :freeboard/viewport]) [sx sy])]
    (swap! app update :board b/add-item
           {:item/kind :sticky :item/x wx :item/y wy :item/w 180 :item/h 120
            :item/fill "#ffeb8a" :text/runs [{:text ""}]})
    (present!)))

(defn ^:export add-image
  "Demo/verification of the texture path: generate a procedural RGBA checkerboard,
   register it as a kami texture, and drop an image item that samples it."
  [sx sy]
  (when-let [be @backend]
    (let [w 64 h 64
          bytes (vec (mapcat (fn [i] (let [x (mod i w) y (quot i w)
                                           on? (even? (+ (quot x 8) (quot y 8)))]
                                       (if on? [40 120 200 255] [245 245 245 255])))
                             (range (* w h))))
          [wx wy] (world-at sx sy)
          id (b/gen-id)]
      (gpu/register-texture! be "freeboard:demo-img" w h bytes)
      (swap! app update :board b/add-item
             {:item/id id :item/kind :image :item/x wx :item/y wy :item/w 200 :item/h 200
              :image/texture "freeboard:demo-img"})
      (present!))))

(defn ^:export import-doc!
  "Drop a kasane-normalized doc onto the canvas at a screen point."
  [kasane-doc sx sy]
  (let [[wx wy] (b/screen->world (get-in @app [:board :freeboard/viewport]) [sx sy])]
    (swap! app update :board imp/drop-doc kasane-doc [wx wy])
    (present!)))

(defn ^:export debug-state
  "Introspection for the browser-use-clj debug harness (freeboard.debug):
   a JSON snapshot of live app health — board size, viewport, whether the kami
   GPU host booted (backend) and how many frames were presented. If backend is
   false / frame is 0 the renderer never ran (e.g. missing wasm host / no
   WebGPU) — the board state still mutates, so the app *looks* dead."
  []
  (let [{:keys [board w h frame editing drag]} @app]
    (clj->js {:items     (count (:freeboard/items board))
              :selection (count (b/selection board))
              :viewport  (:freeboard/viewport board)
              :canvas    {:w w :h h}
              :frame     frame
              :editing   (boolean editing)
              :dragging  (boolean drag)
              :backend   (boolean @backend)                    ; kami GPU host bound?
              :kamiHost  (boolean (.-KamiCljHost js/window))    ; wasm GPU host present?
              :webgpu    (boolean (.-gpu js/navigator))})))     ; WebGPU available?

;; ---- canonical EDN interop (kami render-IR + Genko doc) --------------------
(defn ^:export render-ir
  "Current board as the canonical kami render-IR EDN string (ADR-0044)."
  [] (pr-str (rir/board->render-ir (:board @app) [(:w @app) (:h @app)])))

(defn ^:export save-doc
  "Current board as the shared Genko-envelope canvas-document EDN string."
  [] (pr-str (fdoc/board->doc (:board @app))))

(defn ^:export open-doc
  "Load a board from a shared canvas-document EDN string (Genko envelope)."
  [edn-str]
  (swap! app assoc :board (fdoc/doc->board (edn/read-string edn-str)))
  (present!))

(defn ^:export resize [w h]
  (swap! app assoc :w w :h h)
  (when-let [be @backend] (gpu/resize! be w h))
  (present!))

;; ---- inline text editing (DOM overlay) -------------------------------------
(declare commit-edit!)

(defn ^:export begin-edit
  "Open a textarea overlay over an editable item (text/sticky) for inline edit."
  [id]
  (when-let [it (b/item-by-id (:board @app) id)]
    (when (b/editable? (:item/kind it))
      (let [vp (get-in @app [:board :freeboard/viewport])
            [sx sy] (b/world->screen vp [(:item/x it) (:item/y it)])
            z (get vp :zoom) dpr (or js/window.devicePixelRatio 1)
            ta (.createElement js/document "textarea")]
        (set! (.-id ta) "fb-editor")
        (set! (.-value ta) (b/text-of it))
        (set! (.. ta -style -cssText)
              (str "position:fixed;z-index:10;font:14px system-ui;padding:6px;resize:none;"
                   "border:1px solid #caa83a;border-radius:8px;background:#fffef5;"
                   "left:" (/ sx dpr) "px;top:" (/ sy dpr) "px;"
                   "width:" (/ (* (:item/w it) z) dpr) "px;height:" (/ (* (:item/h it) z) dpr) "px;"))
        (.appendChild (.-body js/document) ta)
        (.focus ta)
        (swap! app assoc :editing id)
        (.addEventListener ta "blur" (fn [_] (commit-edit!)))
        (.addEventListener ta "keydown"
                           (fn [e] (when (and (= "Enter" (.-key e)) (not (.-shiftKey e)))
                                     (.preventDefault e) (commit-edit!))))))))

(defn commit-edit! []
  (when-let [id (:editing @app)]
    (when-let [ta (.getElementById js/document "fb-editor")]
      (swap! app update :board b/set-text id (.-value ta))
      (.remove ta))
    (swap! app assoc :editing nil)
    (present!)))

(defn ^:export on-double-click [sx sy]
  (when-let [hit (b/hit-test-screen (:board @app) [sx sy])]
    (begin-edit (:item/id hit))))

;; ---- boot ------------------------------------------------------------------
(defn ^:export boot
  "Boot against a canvas element id: create the kami.gpu browser backend
   (kami-clj-host wasm → kami-render), register freeboard assets once, then
   present. Called from index.html after WebGPU is available."
  [canvas-id]
  (-> (kb/make {:canvas canvas-id})
      (.then (fn [be]
               (reset! backend be)
               (gpu/ensure-assets! be {:snapshot/assets (:snapshot/assets (sc/scene-snapshot (:board @app)))})
               (present!)
               (js/console.log "freeboard booted")))))
