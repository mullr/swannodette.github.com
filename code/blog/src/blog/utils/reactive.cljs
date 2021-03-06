(ns blog.utils.reactive
  (:refer-clojure :exclude [map filter remove distinct])
  (:require [goog.events :as events]
            [goog.events.EventType]
            [goog.dom :as gdom]
            [cljs.core.async :refer [>! <! chan put! close!]]
            [blog.utils.helpers :refer [index-of]]
            [blog.utils.dom :as dom])
  (:require-macros [cljs.core.async.macros :refer [go alt!]])
  (:import goog.events.EventType))

(defn atom? [x]
  (instance? Atom x))

(def keyword->event-type
  {:keyup goog.events.EventType.KEYUP
   :keydown goog.events.EventType.KEYDOWN
   :keypress goog.events.EventType.KEYPRESS
   :click goog.events.EventType.CLICK
   :dblclick goog.events.EventType.DBLCLICK
   :mouseover goog.events.EventType.MOUSEOVER
   :mouseout goog.events.EventType.MOUSEOUT
   :mousemove goog.events.EventType.MOUSEMOVE})

(defn listen
  ([el type] (listen el type false))
  ([el type prevent-default?]
    (let [out (chan)]
      (events/listen el (keyword->event-type type)
        (fn [e]
          (if (atom? prevent-default?)
            (when @prevent-default?
              (.preventDefault e))
            (when prevent-default?
              (.preventDefault e)))
          (put! out e)))
      out)))

(defn map [f in]
  (let [out (chan)]
    (go (loop []
          (if-let [x (<! in)]
            (do (>! out (f x))
              (recur))
            (close! out))))
    out))

(defn filter [pred in]
  (let [out (chan)]
    (go (loop []
          (if-let [x (<! in)]
            (do (when (pred x) (>! out x))
              (recur))
            (close! out))))
    out))

(defn remove [f source]
  (let [out (chan)]
    (go (loop []
          (if-let [v (<! source)]
            (do (when-not (f v) (>! out v))
              (recur))
            (close! out))))
    out))

(defn distinct [in]
  (let [out (chan)]
    (go (loop [last nil]
          (if-let [x (<! in)]
            (do (when (not= x last) (>! out x))
              (recur x))
            (close! out))))
    out))

(defn fan-in [ins]
  (let [out (chan)]
    (go (while true
          (let [[x] (alts! ins)]
            (>! out x))))
    out))

(defn toggle [in]
  (let [out (chan)
        control (chan)]
    (go (loop [on true]
          (recur
            (alt!
              in ([x] (when on (>! out x)) on)
              control ([x] x)))))
    {:chan out
     :control control}))

(defn mouse-enter [el]
  (let [matcher (dom/el-matcher el)]
    (->> (listen el :mouseover)
      (filter
        (fn [e]
          (and (identical? el (.-target e))
            (if-let [rel (.-relatedTarget e)] 
              (nil? (gdom/getAncestor rel matcher))
              true))))
      (map (constantly :enter)))))

(defn mouse-leave [el]
  (let [matcher (dom/el-matcher el)]
    (->> (listen el :mouseout)
      (filter
        (fn [e]
          (and (identical? el (.-target e))
            (if-let [rel (.-relatedTarget e)]
              (nil? (gdom/getAncestor rel matcher))
              true))))
      (map (constantly :leave)))))

(defn hover [el]
  (distinct (fan-in [(mouse-enter el) (mouse-leave el)])))

(defn hover-child [el tag]
  (let [matcher (dom/tag-match tag)
        matches (dom/by-tag-name el tag)
        over (->> (listen el :mouseover)
               (map
                 #(let [target (.-target %)]
                    (if (matcher target)
                      target
                      (if-let [el (gdom/getAncestor target matcher)]
                        el
                        :no-match))))
               (remove #{:no-match})
               (map #(index-of matches %)))
        out (->> (listen el :mouseout)
              (filter
                (fn [e]
                  (and (matcher (.-target e))
                       (not (matcher (.-relatedTarget e))))))
              (map (constantly :clear)))]
    (distinct (fan-in [over out]))))
