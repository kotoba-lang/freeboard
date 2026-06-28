# freeboard

Apple **Freeform** 風の無限キャンバス・ボード。**clj/cljc が頭脳**（ボードモデル + 操作）、
**GPU 描画は kami-render(Rust/wgpu)** を `kami-engine-sdk-clj` の render-IR 経由で、
**kasane** が PSD/PDF/PNG/SVG/Sketch/… をキャンバスに取り込み、**kotoba** が
content-addressed な永続/共同編集層 — という分担（`kami-app-sip-clj` と同じ流儀）。

SSoT: `90-docs/adr/2606280200-freeboard-infinite-canvas.md`（superproject 側）

## 構成

| ns | 役割 | テスト |
|---|---|---|
| `freeboard.board` (cljc) | ボード文書 + ビューポート(pan/zoom) + アイテム CRUD + world↔screen + hit-test | ✅ bb |
| `freeboard.import` (cljc) | kasane `:kasane/doc` → ボードアイテム（ドロップ配置） | ✅ bb |
| `freeboard.render` (cljc) | ボード → **kami render-IR**（screen-space draw-list, 2D = kami-ui-gpu）+ ECS entity 変換 | ✅ bb |
| `freeboard.schema` (cljc) | malli = 文書 SSoT（検証） | （malli alias） |
| `freeboard.web` (cljs) | ブラウザ入力→操作→毎フレーム render-IR を kami host へ | scaffold |

無限キャンバス数学（純粋・検証済み）:
- `screen = (world - pan) * zoom` / `world = pan + screen/zoom`
- `zoom-at` はカーソル下のワールド点を固定したままズーム
- `hit-test` は z 最大のアイテムを返す

## 使い方（モデル）

```clojure
(require '[freeboard.board :as b] '[freeboard.import :as imp] '[freeboard.render :as r])
(-> (b/new-board "My board")
    (b/add-item {:item/kind :sticky :item/x 100 :item/y 100 :item/w 180 :item/h 120 :item/fill "#ffeb8a"})
    (imp/drop-doc kasane-doc [400 200])   ; kasane.normalize の出力をドロップ
    (r/draw-list))                         ; → {:clear [...] :draws [...]}  (kami-render が実行)
```

## テスト / ビルド

```bash
bb test                          # 純 cljc コア（外部依存なし）
clojure -M:test ...              # JVM
npx shadow-cljs release app      # ブラウザ（public/js/freeboard.js）
```

## 状態（正直に）

- **モデル/インポート/render-IR 生成は実装・bb 検証済み**（5 tests / 24 assertions green）。
- **ブラウザ描画**は kami-render(wgpu) wasm host への接続が次フェーズ（`index.html` の
  `kami-host.js` boot + shadow ビルド）。`freeboard.web` は入力→操作→draw-list までを担う scaffold。
- **永続/共同編集**（kotoba QuadStore + CACAO）は設計済み・未配線（ADR 参照）。
