(ns freeboard.scene
  "Board → kami ECS scene (entities + camera + assets) for kami-engine-sdk-clj.
   Each board item becomes a renderable entity carrying the exact component
   attrs kami.render queries (`:kami/eid` `:transform/translation`
   `:transform/scale` `:mesh/asset` `:material/asset` `:material/tint`); per-item
   fill → per-instance tint (v2 packing). The viewport (pan/zoom) is baked into
   screen-space transforms (2D). web.cljs feeds these to kami.ecs/world →
   kami.render/frame → kami.gpu/submit!. See ADR-2606280200."
  (:require [freeboard.board :as b]
            [freeboard.render :as r]))

(defn hex->rgba
  "\"#rrggbb\" → [r g b 1.0] floats (nil/invalid → white)."
  [hex]
  (if (and (string? hex) (= 7 (count hex)) (= \# (first hex)))
    (let [p (fn [i] (/ (#?(:clj Integer/parseInt :cljs js/parseInt) (subs hex i (+ i 2)) 16) 255.0))]
      [(p 1) (p 3) (p 5) 1.0])
    [1.0 1.0 1.0 1.0]))

(def kind-tint
  {:sticky [1.0 0.92 0.54 1.0] :frame [0.97 0.95 0.86 1.0] :text [1.0 1.0 1.0 1.0]
   :shape  [0.80 0.85 1.0 1.0] :image [0.90 0.90 0.90 1.0]
   :connector [0.5 0.5 0.5 1.0] :ink [0.13 0.13 0.13 1.0]})

(defn- draw->entity [d]
  (let [[sx sy sw sh] (:rect d)]
    {:kami/eid               (:eid d)
     :transform/translation  [sx sy (* 0.001 (:z d))]         ; small z for stable layering
     :transform/scale        [(max 1.0 sw) (max 1.0 sh) 1.0]
     :mesh/asset             "freeboard:quad"
     :material/asset         "freeboard:flat"
     :material/tint          (if (:fill d) (hex->rgba (:fill d))
                                 (kind-tint (:kind d) [1.0 1.0 1.0 1.0]))}))

;; ---- lines as quads (no separate line pipeline needed) --------------------
(defn- sincos [a] #?(:clj [(Math/sin a) (Math/cos a)] :cljs [(js/Math.sin a) (js/Math.cos a)]))
(defn- atan2 [y x] #?(:clj (Math/atan2 y x) :cljs (js/Math.atan2 y x)))
(defn- hypot [x y] #?(:clj (Math/hypot x y) :cljs (js/Math.hypot x y)))

(defn seg->entity
  "A screen-space segment [[x1 y1][x2 y2]] of width `w` → a rotated thin quad
   entity (corner-origin unit quad, scale [L w 1], z-rotation θ, translated so
   the quad is centred on the segment)."
  [eid [[x1 y1] [x2 y2]] w z tint]
  (let [dx (- x2 x1) dy (- y2 y1)
        L  (max 1.0 (hypot dx dy))
        th (atan2 dy dx)
        [s c] (sincos th)
        mx (/ (+ x1 x2) 2.0) my (/ (+ y1 y2) 2.0)
        ;; translation = midpoint - R(θ)·[L/2, w/2]
        ox (- mx (- (* c (/ L 2.0)) (* s (/ w 2.0))))
        oy (- my (+ (* s (/ L 2.0)) (* c (/ w 2.0))))]
    {:kami/eid              eid
     :transform/translation [ox oy (* 0.001 z)]
     :transform/rotation    [0.0 0.0 (#?(:clj Math/sin :cljs js/Math.sin) (/ th 2.0))
                             (#?(:clj Math/cos :cljs js/Math.cos) (/ th 2.0))]
     :transform/scale       [L (max 1.0 w) 1.0]
     :mesh/asset            "freeboard:quad"
     :material/asset        "freeboard:flat"
     :material/tint         tint}))

(defn- line-entities [d]
  (let [tint (if (:stroke d) (hex->rgba (:stroke d)) (:connector kind-tint))
        z    (:z d)]
    (case (:kind d)
      :connector (when-let [ln (:connector/line d)] [(seg->entity (str (:eid d) ":l") ln 2.0 z tint)])
      :ink       (let [pts (:ink/polyline d) w (:ink/width d 2.0)]
                   (map-indexed (fn [i seg] (seg->entity (str (:eid d) ":" i) seg w z tint))
                                (partition 2 1 pts)))
      nil)))

(defn board->entities
  "Renderable quad entities. Rect items → one quad; connectors/ink → thin quad
   segments (so they render on the same quad+tint pipeline, no line pipeline)."
  [board]
  (let [draws (:draws (r/draw-list board))]
    (into (mapv draw->entity (remove #(#{:connector :ink} (:kind %)) draws))
          (mapcat line-entities (filter #(#{:connector :ink} (:kind %)) draws)))))

(defn camera-entity
  "Active camera. 2D board uses a positioned perspective camera (ortho camera-ir
   is a small SDK refinement — see ADR); items live in the z≈0 screen plane."
  []
  {:kami/eid :freeboard/camera :camera/active? true
   :camera/fov 60.0 :camera/near 0.1 :camera/far 100000.0
   :transform/translation [0.0 0.0 1000.0]})

(def quad-mesh
  ;; unit quad [0,0]→[1,1], pos-only (final vertex layout tuned to kami-render's
  ;; 2D vertex shader at integration).
  {:asset/id "freeboard:quad" :asset/kind :mesh
   :vertices [0.0 0.0 0.0  1.0 0.0 0.0  1.0 1.0 0.0  0.0 1.0 0.0]
   :indices  [0 1 2  0 2 3]})

(def flat-material {:asset/id "freeboard:flat" :asset/kind :material :params []})

(defn scene-snapshot
  "{:snapshot/assets [...] :snapshot/entities [...]} — assets via
   kami.gpu/ensure-assets!, entities via kami.ecs/world."
  [board]
  {:snapshot/assets   [quad-mesh flat-material]
   :snapshot/entities (conj (board->entities board) (camera-entity))})
