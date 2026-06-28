(ns freeboard.ops-test
  (:require [clojure.test :refer [deftest is testing]]
            [freeboard.board :as b]
            [freeboard.render :as r]
            [freeboard.snapshot :as snap]))

(defn- two-items []
  (-> (b/new-board)
      (b/add-item {:item/id "a" :item/kind :sticky :item/x 0 :item/y 0 :item/w 100 :item/h 100})
      (b/add-item {:item/id "c" :item/kind :sticky :item/x 300 :item/y 0 :item/w 100 :item/h 100})))

(deftest connectors
  (let [bd (-> (two-items) (b/add-connector "a" "c"))
        conn (last (:freeboard/items bd))]
    (is (= :connector (:item/kind conn)))
    (testing "endpoints = item centers, and follow items when moved"
      (is (= [[50.0 50.0] [350.0 50.0]] (b/connector-endpoints bd conn)))
      (let [bd (b/move-item bd "a" 0 100)]
        (is (= [[50.0 150.0] [350.0 50.0]] (b/connector-endpoints bd conn)))))
    (testing "render emits a screen-space line; vanishes if endpoint deleted"
      (let [dl (r/draw-list bd)
            cd (first (filter #(= :connector (:kind %)) (:draws dl)))]
        (is (= [[50.0 50.0] [350.0 50.0]] (:connector/line cd))))
      (let [bd (b/delete-item bd "a")
            dl (r/draw-list bd)]
        (is (empty? (filter #(= :connector (:kind %)) (:draws dl))))))))

(deftest ink
  (let [bd (-> (b/new-board) (b/add-ink [[10 10] [20 30] [40 15]] 3.0 "#222"))
        it (last (:freeboard/items bd))]
    (is (= :ink (:item/kind it)))
    (is (= [10 10 30 20] [(:item/x it) (:item/y it) (:item/w it) (:item/h it)]))  ; bbox
    (testing "extend updates points + bbox"
      (let [bd (b/extend-ink bd (:item/id it) [5 50])
            it (b/item-by-id bd (:item/id it))]
        (is (= 4 (count (:ink/points it))))
        (is (= [5 10 35 40] [(:item/x it) (:item/y it) (:item/w it) (:item/h it)]))))))

(deftest text-edit
  (let [bd (-> (b/new-board) (b/add-item {:item/id "t" :item/kind :text :item/x 0 :item/y 0 :item/w 100 :item/h 30}))
        bd (b/set-text bd "t" "Hello")]
    (is (= [{:text "Hello"}] (:text/runs (b/item-by-id bd "t"))))
    (is (= [{:text "x"} {:text "y"}] (:text/runs (b/item-by-id (b/set-text bd "t" [{:text "x"} {:text "y"}]) "t"))))
    (testing "text-of concatenates runs; editable? gates the inline editor"
      (is (= "Hello" (b/text-of (b/item-by-id bd "t"))))
      (is (= "xy" (b/text-of (b/item-by-id (b/set-text bd "t" [{:text "x"} {:text "y"}]) "t"))))
      (is (b/editable? :text)) (is (b/editable? :sticky)) (is (not (b/editable? :connector))))))

(deftest snapshot-roundtrip
  (let [bd (-> (two-items) (b/add-connector "a" "c") (b/set-text "a" "note")
               (assoc :freeboard/title "My Board"))]
    (testing "EDN round-trip"
      (is (= bd (snap/from-edn (snap/->edn bd)))))
    (testing "datom projection round-trip (items + board attrs)"
      (let [bd2 (snap/datoms->board (snap/board->datoms bd))]
        (is (= (:freeboard/title bd) (:freeboard/title bd2)))
        (is (= (set (map :item/id (:freeboard/items bd)))
               (set (map :item/id (:freeboard/items bd2)))))
        (is (= "note" (-> (b/item-by-id bd2 "a") :text/runs first :text)))))))
