(ns freeboard.board
  "Freeboard — an Apple-Freeform-style infinite-canvas board model. Pure cljc:
   the board document + viewport (pan/zoom) + item CRUD + world↔screen math +
   hit-testing. The brain is clj/cljc (this); rendering is kami-render (wgpu)
   via kami-engine-sdk-clj render-IR (see freeboard.render); persistence is
   kotoba (content-addressed). See ADR-2606280200."
  #?(:clj (:require [clojure.string :as str])))

;; ---- document --------------------------------------------------------------
(defn new-board
  ([] (new-board "Untitled"))
  ([title]
   {:freeboard/version 1
    :freeboard/title   title
    :freeboard/viewport {:x 0.0 :y 0.0 :zoom 1.0}            ; world coord at screen origin + zoom
    :freeboard/items   []
    :freeboard/next-z  0}))

(def item-kinds #{:sticky :text :shape :connector :frame :image :ink})

;; ---- viewport / coordinate transforms -------------------------------------
;; screen = (world - pan) * zoom ;  world = pan + screen / zoom
(defn world->screen [{:keys [x y zoom]} [wx wy]]
  [(* (- wx x) zoom) (* (- wy y) zoom)])

(defn screen->world [{:keys [x y zoom]} [sx sy]]
  [(+ x (/ sx zoom)) (+ y (/ sy zoom))])

(defn pan [board dx-screen dy-screen]
  ;; drag the canvas by a screen delta (world moves opposite / zoom-scaled)
  (let [{:keys [zoom]} (:freeboard/viewport board)]
    (-> board
        (update-in [:freeboard/viewport :x] - (/ dx-screen zoom))
        (update-in [:freeboard/viewport :y] - (/ dy-screen zoom)))))

(defn zoom-at
  "Zoom to `new-zoom` (clamped) keeping the world point under screen point
   [sx sy] fixed."
  [board new-zoom [sx sy]]
  (let [vp   (:freeboard/viewport board)
        z    (max 0.05 (min 64.0 new-zoom))
        [wx wy] (screen->world vp [sx sy])]
    (assoc board :freeboard/viewport
           {:x (- wx (/ sx z)) :y (- wy (/ sy z)) :zoom z})))

;; ---- items -----------------------------------------------------------------
(defn- gen-id []
  #?(:clj (str (java.util.UUID/randomUUID))
     :cljs (str (random-uuid))))

(defn add-item
  "Add an item map (kind + geometry) to the board, assigning id + z if absent."
  [board item]
  (let [z (:freeboard/next-z board)
        it (cond-> item
             (nil? (:item/id item)) (assoc :item/id (gen-id))
             (nil? (:item/z item))  (assoc :item/z z))]
    (-> board
        (update :freeboard/items conj it)
        (update :freeboard/next-z inc))))

(defn item-by-id [board id]
  (first (filter #(= id (:item/id %)) (:freeboard/items board))))

(defn update-item [board id f & args]
  (update board :freeboard/items
          (fn [items] (mapv (fn [it] (if (= id (:item/id it)) (apply f it args) it)) items))))

(defn move-item  [board id dx dy] (update-item board id #(-> % (update :item/x + dx) (update :item/y + dy))))
(defn resize-item [board id w h]  (update-item board id assoc :item/w (max 1.0 w) :item/h (max 1.0 h)))
(defn delete-item [board id]
  (update board :freeboard/items (fn [items] (vec (remove #(= id (:item/id %)) items)))))

(defn bring-to-front [board id]
  (let [z (:freeboard/next-z board)]
    (-> board (update-item id assoc :item/z z) (update :freeboard/next-z inc))))

;; ---- hit testing -----------------------------------------------------------
(defn- in-rect? [{:item/keys [x y w h]} [wx wy]]
  (and (<= x wx (+ x w)) (<= y wy (+ y h))))

(defn hit-test
  "Topmost item (highest :item/z) whose world bbox contains world point, or nil."
  [board world-pt]
  (->> (:freeboard/items board)
       (filter #(in-rect? % world-pt))
       (sort-by :item/z >)
       first))

(defn hit-test-screen [board screen-pt]
  (hit-test board (screen->world (:freeboard/viewport board) screen-pt)))

;; ---- z-ordered view --------------------------------------------------------
(defn items-z-asc [board] (sort-by :item/z (:freeboard/items board)))
