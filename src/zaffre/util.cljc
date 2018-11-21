(ns zaffre.util
  (:require [zaffre.terminal :as zat]
            [clojure.core.async :as async :refer [>! <! chan to-chan timeout go go-loop thread]]
            clojure.set)
  (:import (zaffre.terminal Terminal)))

(defn next-pow-2 [v]
  (int (Math/pow 2 (Math/ceil (/ (Math/log v) (Math/log 2))))))

(defn mk-string
  ([x y string]
   (mk-string (int (Math/ceil x)) (int (Math/ceil y)) string [255 255 255] [0 0 0] #{}))
  ([x y string fg bg]
   (mk-string (int (Math/ceil x)) (int (Math/ceil y)) string fg bg #{}))
  ([x y string fg bg styles]
   {:pre [(clojure.set/superset? #{:underline :bold} styles)]}
   (map-indexed (fn [i c] {:c  c
                           :fg fg
                           :bg bg
                           :x  (+ x i)
                           :y  y})
                string)))
(defn put-string
  ([^Terminal screen layer-id x y string]
   (put-string screen layer-id (int (Math/ceil x)) (int (Math/ceil y)) string [255 255 255] [0 0 0] #{}))
  ([^Terminal screen layer-id x y string fg bg]
   (put-string screen layer-id (int (Math/ceil x)) (int (Math/ceil y)) string fg bg #{}))
  ([^Terminal screen layer-id x y string fg bg styles]
   (zat/put-chars! screen layer-id (mk-string x y string fg bg styles))))


;; Memoized function that returns the points between
;; `[x1 y1]` and `[x2 y2]`
(def line-segment (memoize
  (fn
   [start end]
   (if (= start end)
      []
      (let [[x1 y1] start
            [x2 y2] end
            xdiff (- x2 x1)
            ydiff (- y2 y1)
            maxdiff (max (Math/abs xdiff) (Math/abs ydiff))
            dx (/ xdiff maxdiff)
            dy (/ ydiff maxdiff)]
        (map (fn [i] [(Math/round (double (+ x1 (* i dx)))) (Math/round (double (+ y1 (* i dy))))])
            (range (inc maxdiff))))))))

;; A fast version of `line-segment`. Internally, shift the values so that
;; `[x1 y1]` equals `[0 0]`, call `line-segment` and then shift everything back.
;; It's fast because `(line-segment-fast [0 0] [5 5])` is effectively the same
;; as `(line-segment [2 2] [7 7])` which plays nicely with memoization.
(def line-segment-fast (memoize
  (fn [start end]
    "(line-segment-fast [1 1] [5 4])"
    (let [[ox oy] start
          [dx dy] end]
      (map (fn [[x y]] [(+ ox x) (+ oy y)])
          (line-segment [0 0] [(- dx ox) (- dy oy)]))))))

;; A fast version of `line-segment`. Internally, shift the values so that
;; `[x1 y1]` equals `[0 0]`, call `line-segment` and then shift everything back.
;; It's fast because `(line-segment-fast [0 0] [5 5])` is effectively the same
;; as `(line-segment [2 2] [7 7])` which plays nicely with memoization.
(def line-segment-fast-without-endpoints (memoize
  (fn [start end]
    "(line-segment-fast [1 1] [5 4])"
    (let [[ox oy] start
          [dx dy] end]
      (rest
        (butlast
          (map (fn [[x y]] [(+ ox x) (+ oy y)])
               (line-segment [0 0] [(- dx ox) (- dy oy)]))))))))

(defn dot [u v]
  (reduce + 0 (map * u v)))

(defn norm [v]
  (Math/sqrt (reduce + 0 (map (fn [x] (* x x)) v))))

(defn unit [v]
  (let [l (norm v)]
    (map #(/ % l) v)))

(defn light-intensity [dx dy dz]
  (let [rl  [dx dy dz]
        d   (norm rl) ; distance from [x y] to light
        i (min 1.0 (/ (* 1.0 (dot (unit [0 0 1]) (unit rl))) (* d d)))]
    i))
 

(defn cycle [open-chan chan-fn]
  (fn []
    (go-loop []
       (<! (chan-fn))
       (when (= :open (<! open-chan))
         (recur)))))

(defn parallel [& chans]
  (fn []
    (go
      (<! (async/merge (map (fn [c] (c)) chans))))))

(defn keys-to-leaves [prefix m]
  (mapcat (fn [[k v]]
            (if (map? v)
              (keys-to-leaves (conj prefix k) v)
              [[(conj prefix k) v]]))
          m))

(defn transpose [xs]
  (apply map list xs))

(defn- map-generator [steps [ks g]]
  (map (fn [v] [ks v])
       (if (fn? g)
         (g steps)
         (repeat steps g))))

(defn- reduce-event [event]
  (reduce (fn [m [ks v]]
            (assoc-in m ks v))
          {}
          event))

(defn- sequence-cmd [state-chan cmd next-cmd]
  ;; maps are prop generators
  (let [generators (keys-to-leaves [] cmd)
        dt 33
        duration (if (number? next-cmd) next-cmd 1)
        steps (int (/ duration dt))
        events (transpose
                 (map (partial map-generator steps)
                      generators))
        state-changes (map reduce-event
                           events)
        change-cmds (interpose dt state-changes)]
    (go
      (doseq [cmd change-cmds]
        (if (number? cmd)
          (<! (timeout cmd))
          (>! state-chan cmd))))))

(defn sequence [state-chan & cmds]
  (fn [] (go
    (doseq [[cmd next-cmd] (map list
                             cmds
                             (concat (rest cmds) [(first cmds)]))]
      (cond
        (number? cmd)
          ;; numbers are pauses
          (<! (timeout cmd))
        (and (map? cmd) (number? next-cmd))
          (sequence-cmd state-chan cmd next-cmd) 
        :default
           (assert false (str "cmd must be map or number. found:" cmd " " next-cmd)))))))

