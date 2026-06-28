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

(defn item-center [{:item/keys [x y w h]}] [(+ x (/ w 2.0)) (+ y (/ h 2.0))])

;; ---- connectors ------------------------------------------------------------
(defn add-connector
  "Connect two items by id. The connector carries no fixed geometry — its
   endpoints are resolved from the linked items at render time (so it follows
   them when they move)."
  [board from-id to-id]
  (add-item board {:item/kind :connector :connector/from from-id :connector/to to-id
                   :item/x 0 :item/y 0 :item/w 0 :item/h 0 :item/stroke "#888"}))

(defn connector-endpoints
  "World-space [[x1 y1] [x2 y2]] for a connector, from its linked items' centers
   (nil if either endpoint is missing)."
  [board conn]
  (let [a (item-by-id board (:connector/from conn))
        b (item-by-id board (:connector/to conn))]
    (when (and a b) [(item-center a) (item-center b)])))

;; ---- ink (freehand) --------------------------------------------------------
(defn- points-bbox [pts]
  (let [xs (map first pts) ys (map second pts)]
    [(apply min xs) (apply min ys) (- (apply max xs) (apply min xs)) (- (apply max ys) (apply min ys))]))

(defn add-ink
  "Add a freehand ink stroke from world-space points."
  ([board points] (add-ink board points 2.0 "#222"))
  ([board points width stroke]
   (let [[x y w h] (points-bbox points)]
     (add-item board {:item/kind :ink :ink/points (vec points) :ink/width width
                      :item/stroke stroke :item/x x :item/y y :item/w (max 1.0 w) :item/h (max 1.0 h)}))))

(defn extend-ink
  "Append a world point to an ink item, updating its bbox."
  [board id pt]
  (update-item board id
               (fn [it] (let [pts (conj (:ink/points it []) pt)
                              [x y w h] (points-bbox pts)]
                          (assoc it :ink/points pts :item/x x :item/y y
                                 :item/w (max 1.0 w) :item/h (max 1.0 h))))))

;; ---- text ------------------------------------------------------------------
(defn set-text
  "Replace an item's text runs (plain string → single run)."
  [board id text]
  (update-item board id assoc :text/runs (if (string? text) [{:text text}] (vec text))))

(defn text-of
  "Plain concatenated text of an item's runs (\"\" if none)."
  [it]
  (apply str (map :text (:text/runs it))))

(def editable? #{:text :sticky})

;; ---- selection -------------------------------------------------------------
(defn selection [board] (:freeboard/selection board #{}))
(defn select [board ids] (assoc board :freeboard/selection (set ids)))
(defn select-toggle [board id]
  (update board :freeboard/selection (fn [s] (let [s (or s #{})] (if (s id) (disj s id) (conj s id))))))
(defn clear-selection [board] (assoc board :freeboard/selection #{}))
(defn selected? [board id] (contains? (selection board) id))

(defn- rects-overlap? [[ax ay aw ah] [bx by bw bh]]
  (and (< ax (+ bx bw)) (< bx (+ ax aw)) (< ay (+ by bh)) (< by (+ ay ah))))

(defn select-in-rect
  "Rubber-band select: every item whose bbox overlaps the world rect [x y w h]."
  [board [x y w h]]
  (select board (->> (:freeboard/items board)
                     (filter #(rects-overlap? [x y w h] [(:item/x %) (:item/y %) (:item/w %) (:item/h %)]))
                     (map :item/id))))

(defn move-selection [board dx dy]
  (reduce (fn [bd id] (move-item bd id dx dy)) board (selection board)))

;; ---- grouping --------------------------------------------------------------
(defn- enclosing-bbox [items]
  (let [xs (map :item/x items) ys (map :item/y items)
        x2 (map #(+ (:item/x %) (:item/w %)) items) y2 (map #(+ (:item/y %) (:item/h %)) items)
        x (apply min xs) y (apply min ys)]
    [x y (- (apply max x2) x) (- (apply max y2) y)]))

(defn group-selection
  "Wrap the current selection in a :group item (bbox encloses members); members
   are tagged with :item/group <group-id>. No-op for <2 selected."
  [board]
  (let [ids (selection board)
        members (filter #(ids (:item/id %)) (:freeboard/items board))]
    (if (< (count members) 2)
      board
      (let [[x y w h] (enclosing-bbox members)
            gid (gen-id)
            bd (add-item board {:item/id gid :item/kind :group :item/x x :item/y y :item/w w :item/h h
                                :group/members (vec ids) :item/stroke "#caa83a"})]
        (reduce (fn [b id] (update-item b id assoc :item/group gid)) bd ids)))))

(defn ungroup
  "Remove a group item, clearing its members' :item/group tag."
  [board gid]
  (let [members (:group/members (item-by-id board gid))]
    (-> (reduce (fn [b id] (update-item b id dissoc :item/group)) board members)
        (delete-item gid))))

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
