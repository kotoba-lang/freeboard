(ns freeboard.kotoba
  "Browser durable client — content-addressed board snapshots over Kotoba's
   block XRPC (block.put / block.get), mirroring kami-app-sip-clj's sip.kotoba.
   Backend is chosen at runtime:
     • window.FREEBOARD_KOTOBA_URL set → real Kotoba server (block XRPC).
     • otherwise → a localStorage content-addressed store (works with no server).
   The board body is the canonical EDN from freeboard.snapshot (CAS), so it
   round-trips identically on either backend. Collaborative multi-writer sync
   (kotoba CommitDag + CACAO) is the follow-up. All ops return a core.async
   channel. See ADR-2606280200."
  (:require [clojure.string :as str]
            [cljs.core.async :as a :refer [<!]]
            [freeboard.snapshot :as snap])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn- chan-of [v] (let [c (a/chan 1)] (when (some? v) (a/put! c v)) (a/close! c) c))
(defn- base-url []
  (let [u (when (exists? js/window) (aget js/window "FREEBOARD_KOTOBA_URL"))]
    (when (and (string? u) (not (str/blank? u))) u)))

(defn- utf8->b64 [s] (js/btoa (js/unescape (js/encodeURIComponent s))))
(defn- b64->utf8 [b] (js/decodeURIComponent (js/escape (js/atob b))))

;; --- content address (blake3-ish stand-in: stable hash of the EDN bytes) ----
(defn- cid [^string edn]
  ;; FNV-1a 32-bit hex — deterministic local CID; the real server returns a
  ;; CIDv1 (blake3) from block.put, which we prefer when present.
  (loop [i 0 h 2166136261]
    (if (>= i (count edn))
      (str "fb1-" (.toString (unsigned-bit-shift-right h 0) 16))
      (recur (inc i) (-> (bit-xor h (.charCodeAt edn i)) (* 16777619) (bit-and 0xffffffff))))))

;; --- localStorage CAS -------------------------------------------------------
(defn- ls-put [k v] (.setItem js/localStorage k v))
(defn- ls-get [k]   (.getItem js/localStorage k))

(defn save-board!
  "Persist a board snapshot. Returns a channel yielding its CID."
  [board]
  (let [edn (snap/->edn board) id (cid edn)]
    (if-let [url (base-url)]
      (let [c (a/chan 1)]
        (-> (js/fetch (str url "/xrpc/com.etzhayyim.apps.kotoba.block.put")
                      #js {:method "POST" :headers #js {"content-type" "application/json"}
                           :body (js/JSON.stringify #js {:data (utf8->b64 edn)})})
            (.then #(.json %))
            (.then (fn [r] (a/put! c (or (aget r "cid") id)) (a/close! c))))
        c)
      (do (ls-put (str "freeboard/" id) edn)
          (ls-put "freeboard/head" id)
          (chan-of id)))))

(defn load-board
  "Load a board by CID. Returns a channel yielding the board (or nil)."
  [id]
  (if-let [url (base-url)]
    (let [c (a/chan 1)]
      (-> (js/fetch (str url "/xrpc/com.etzhayyim.apps.kotoba.block.get?cid=" id))
          (.then #(.json %))
          (.then (fn [r] (a/put! c (some-> (aget r "data") b64->utf8 snap/from-edn)) (a/close! c))))
      c)
    (chan-of (some-> (ls-get (str "freeboard/" id)) snap/from-edn))))

(defn load-head
  "Load the last locally-saved board (localStorage backend), or nil."
  []
  (go (when-let [id (ls-get "freeboard/head")] (<! (load-board id)))))

;; --- collaborative op log ---------------------------------------------------
;; The shared editing surface is an op log (freeboard.collab). Over a real
;; Kotoba server it's a datomic.* transaction feed (ai.gftd.apps.kotobase.
;; datomic.transact / .q, CACAO-authed per board graph); the localStorage
;; backend appends to an EDN log so single-user works offline. pull-ops merges
;; remote ops; freeboard.collab/replay converges.
(defn push-ops!
  "Append ops (a vector) for `board-id`. Returns a channel (unit when done)."
  [board-id ops]
  (if-let [url (base-url)]
    (let [c (a/chan 1)]
      (-> (js/fetch (str url "/xrpc/ai.gftd.apps.kotobase.datomic.transact")
                    #js {:method "POST" :headers #js {"content-type" "application/json"}
                         :body (js/JSON.stringify #js {:graph board-id :ops (utf8->b64 (pr-str ops))})})
          (.then (fn [_] (a/put! c :ok) (a/close! c))))
      c)
    (let [k (str "freeboard/ops/" board-id)
          cur (or (some-> (ls-get k) cljs.reader/read-string) [])]
      (ls-put k (pr-str (into cur ops)))
      (chan-of :ok))))

(defn pull-ops
  "Fetch the op log for `board-id`. Returns a channel yielding a vector of ops."
  [board-id]
  (if-let [url (base-url)]
    (let [c (a/chan 1)]
      (-> (js/fetch (str url "/xrpc/ai.gftd.apps.kotobase.datomic.q?graph=" board-id))
          (.then #(.json %))
          (.then (fn [r] (a/put! c (or (some-> (aget r "ops") b64->utf8 cljs.reader/read-string) [])) (a/close! c))))
      c)
    (chan-of (or (some-> (ls-get (str "freeboard/ops/" board-id)) cljs.reader/read-string) []))))
