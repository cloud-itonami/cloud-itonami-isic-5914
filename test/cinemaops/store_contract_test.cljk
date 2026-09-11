(ns cinemaops.store-contract-test
  "Contract tests for `cinemaops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [cinemaops.store :as store]))

(deftest mem-store-screening-lookup
  (testing "MemStore can store and retrieve screenings by ID (string keys)"
    (let [screenings {"s1" {:screening-id "s1" :film "Reel One" :registered? true :verified? true}}
          s (store/mem-store screenings)]
      (is (some? (store/screening s "s1")))
      (is (nil? (store/screening s "s99"))))))

(deftest mem-store-all-screenings
  (testing "MemStore returns all screenings in sorted order"
    (let [screenings {"s2" {:screening-id "s2" :film "Two"}
                      "s1" {:screening-id "s1" :film "One"}
                      "s3" {:screening-id "s3" :film "Three"}}
          s (store/mem-store screenings)
          all-s (store/all-screenings s)]
      (is (= 3 (count all-s)))
      (is (= "s1" (:screening-id (first all-s))))
      (is (= "s3" (:screening-id (last all-s)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-screening-record :screening-id "s1" :value {:attendance 10}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-screenings
  (testing "MemStore with-screenings replaces the screening directory"
    (let [s (store/mem-store {})
          new-screenings {"s1" {:screening-id "s1" :film "One"}}]
      (is (= 0 (count (store/all-screenings s))))
      (store/with-screenings s new-screenings)
      (is (= 1 (count (store/all-screenings s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo screenings"
    (let [s (store/seed-db)]
      (is (> (count (store/all-screenings s)) 0))
      (is (some? (store/screening s "screening-1")))
      (is (some? (store/screening s "screening-2")))
      (is (some? (store/screening s "screening-3"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for screening-id"
    (let [demo (store/demo-data)
          screenings (:screenings demo)]
      (doseq [[k v] screenings]
        (is (string? k) "keys must be strings")
        (is (string? (:screening-id v)) "screening-id must be string")
        (is (= k (:screening-id v)) "key must match screening-id")))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
