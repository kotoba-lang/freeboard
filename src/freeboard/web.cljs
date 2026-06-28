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
            [kami.ecs :as ecs]
            [kami.render :as kr]
            [kami.gpu :as gpu]
            [kami.backend.browser :as kb]))

(defonce app (atom {:board (b/new-board "Freeboard")
                    :drag  nil :frame 0}))                     ; {:mode :item-id :last [x y]}

;; ---- kami host (kami-render/wgpu via kami-engine-sdk-clj) -------------------
(defonce ^:private backend (atom nil))
(defonce ^:private aspect  (atom (/ 16.0 9.0)))

(defn- present!
  "Build the kami ECS world from the board, assemble one render-IR frame, and
   submit it (v2 packing = per-instance tint). The renderer is a dumb executor."
  []
  (when-let [be @backend]
    (let [snap  (sc/scene-snapshot (:board @app))
          world (ecs/load-snapshot snap)                       ; 1-arg: snapshot → fresh world
          frame (kr/frame world {:n (:frame @app) :aspect @aspect :clear sc/nintendo-cream})]
      (gpu/submit! be frame {:tint? true})
      (swap! app update :frame inc))))

;; ---- input → ops -----------------------------------------------------------
(defn on-pointer-down [sx sy]
  (let [board (:board @app)]
    (if-let [hit (b/hit-test-screen board [sx sy])]
      (swap! app assoc :drag {:mode :move :id (:item/id hit) :last [sx sy]}
                       :board (b/bring-to-front board (:item/id hit)))
      (swap! app assoc :drag {:mode :pan :last [sx sy]})))
  (present!))

(defn on-pointer-move [sx sy]
  (when-let [{:keys [mode id last]} (:drag @app)]
    (let [[lx ly] last dx (- sx lx) dy (- sy ly)
          z (get-in @app [:board :freeboard/viewport :zoom])]
      (swap! app (fn [s]
                   (-> s
                       (update :board (fn [bd]
                                        (case mode
                                          :pan  (b/pan bd dx dy)
                                          :move (b/move-item bd id (/ dx z) (/ dy z)))))
                       (assoc-in [:drag :last] [sx sy]))))
      (present!))))

(defn on-pointer-up [] (swap! app assoc :drag nil))

(defn on-wheel [sx sy delta]
  (let [z (get-in @app [:board :freeboard/viewport :zoom])
        nz (* z (if (neg? delta) 1.1 (/ 1.0 1.1)))]
    (swap! app update :board b/zoom-at nz [sx sy])
    (present!)))

;; ---- toolbar ops -----------------------------------------------------------
(defn add-sticky [sx sy]
  (let [[wx wy] (b/screen->world (get-in @app [:board :freeboard/viewport]) [sx sy])]
    (swap! app update :board b/add-item
           {:item/kind :sticky :item/x wx :item/y wy :item/w 180 :item/h 120
            :item/fill "#ffeb8a" :text/runs [{:text ""}]})
    (present!)))

(defn import-doc!
  "Drop a kasane-normalized doc onto the canvas at a screen point."
  [kasane-doc sx sy]
  (let [[wx wy] (b/screen->world (get-in @app [:board :freeboard/viewport]) [sx sy])]
    (swap! app update :board imp/drop-doc kasane-doc [wx wy])
    (present!)))

(defn ^:export resize [w h]
  (reset! aspect (/ (double w) (max 1.0 (double h))))
  (when-let [be @backend] (gpu/resize! be w h))
  (present!))

;; ---- boot ------------------------------------------------------------------
(defn ^:export boot
  "Boot against a canvas: create the kami.gpu browser backend (kami-clj-host
   wasm → kami-render), register freeboard assets once, then present. Called
   from index.html after WebGPU is available."
  [canvas]
  (-> (kb/create {:canvas canvas})
      (.then (fn [be]
               (reset! backend be)
               (gpu/ensure-assets! be {:snapshot/assets (:snapshot/assets (sc/scene-snapshot (:board @app)))})
               (present!)
               (js/console.log "freeboard booted")))))
