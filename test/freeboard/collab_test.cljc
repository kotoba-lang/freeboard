(ns freeboard.collab-test
  (:require [clojure.test :refer [deftest is testing]]
            [freeboard.board :as b]
            [freeboard.collab :as c]))

(defn- ts [op t] (assoc op :op/ts t))

(deftest apply-ops
  (let [log [(ts (c/op "A" 0 :add-item {:item {:item/id "x" :item/kind :sticky :item/x 0 :item/y 0 :item/w 10 :item/h 10}}) 1)
             (ts (c/op "A" 1 :move {:id "x" :dx 5 :dy 5}) 2)
             (ts (c/op "A" 2 :set-text {:id "x" :text "hi"}) 3)]
        bd (c/replay log)
        it (b/item-by-id bd "x")]
    (is (= 1 (count (:freeboard/items bd))))
    (is (= [5 5] [(:item/x it) (:item/y it)]))
    (is (= "hi" (-> it :text/runs first :text)))))

(deftest convergence
  (testing "two clients' logs converge regardless of merge order"
    (let [base (-> (b/new-board)
                   (b/add-item {:item/id "x" :item/kind :sticky :item/x 0 :item/y 0 :item/w 10 :item/h 10}))
          ;; A moves x; B sets its text + adds its own item — concurrent
          la [(ts (c/op "A" 0 :move {:id "x" :dx 100 :dy 0}) 10)
              (ts (c/op "A" 1 :move {:id "x" :dx 0 :dy 50}) 12)]
          lb [(ts (c/op "B" 0 :set-text {:id "x" :text "hello"}) 11)
              (ts (c/op "B" 1 :add-item {:item {:item/id "y" :item/kind :text :item/x 9 :item/y 9 :item/w 5 :item/h 5}}) 13)]
          ab (c/replay base (c/merge-logs la lb))
          ba (c/replay base (c/merge-logs lb la))]
      (is (= ab ba))                                           ; order-independent convergence
      (is (= [100 50] [(:item/x (b/item-by-id ab "x")) (:item/y (b/item-by-id ab "x"))]))
      (is (= "hello" (-> (b/item-by-id ab "x") :text/runs first :text)))
      (is (some? (b/item-by-id ab "y")))))
  (testing "dedup by op/id is idempotent"
    (let [l [(ts (c/op "A" 0 :move {:id "x" :dx 1 :dy 0}) 1)]]
      (is (= (c/order l) (c/order (concat l l l)))))))
