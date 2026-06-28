(ns freeboard.web
  "Browser entry. Holds board state, turns pointer/wheel input into board ops,
   and submits one render-IR draw-list per frame to the kami-render (wgpu) host.
   The GPU work lives in kami-render via kami-engine-sdk-clj — this file is just
   the brain + input, exactly the kami-app-sip-clj split. See ADR-2606280200.

   NOTE: the kami wasm host attach (`submit-draws!`) binds to
   kami-engine-sdk-clj's browser backend; index.html boots it after wasm init."
  (:require [freeboard.board :as b]
            [freeboard.render :as r]
            [freeboard.import :as imp]))

(defonce app (atom {:board (b/new-board "Freeboard")
                    :drag  nil}))                              ; {:mode :item-id :last [x y]}

;; ---- host bridge (bound to kami-render wasm at boot) ----------------------
(defonce ^:private host (atom nil))                            ; fn: draw-list → unit
(defn set-host! [submit-fn] (reset! host submit-fn))
(defn- present! [] (when-let [h @host] (h (r/draw-list (:board @app)))))

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

;; ---- boot ------------------------------------------------------------------
(defn ^:export boot
  "Called from index.html after the kami-render wasm host is ready. `submit-fn`
   takes a freeboard draw-list and renders it on the GPU."
  [submit-fn]
  (set-host! submit-fn)
  (present!)
  (js/console.log "freeboard booted"))
