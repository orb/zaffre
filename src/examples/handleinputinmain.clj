(ns examples.handleinputinmain
  (:require [zaffre.aterminal :as zat]
            [zaffre.glterminal :as zgl]
            [zaffre.font :as zfont]
            [zaffre.util :as zutil]
            [clojure.core.async :as async :refer [go-loop]]
            [taoensso.timbre :as log])
  (:import (zaffre.aterminal ATerminal)
           (zaffre.font CP437Font TTFFont)))

(defn hsv->rgb [h s v]
  (let [c (* v s)
        x (* c (- 1.0 (Math/abs (double (dec (mod (/ h 60.0) 2.0))))))]
    (mapv (comp int (partial * 255))
      (cond
        (< h  60) [c x 0]
        (< h 120) [x c 0]
        (< h 180) [0 c x]
        (< h 240) [0 x c]
        (< h 300) [x 0 c]
        (< h 360) [c 0 x]))))

(defn -main [& _]
  ;; render in background thread
   (let [colorShift    (atom 0.0001)
         brightness    (atom 0.68)
         contrast      (atom 2.46)
         scanlineDepth (atom 0.94)
         time          (atom 0.0)
         noise         (atom 0.0016)
         terminal   (zgl/make-terminal [:text :rainbow]
                                       {:title "Zaffre demo"
                                        :columns 80 :rows 24
                                        :default-fg-color [250 250 250]
                                        :default-bg-color [5 5 8]
                                        :windows-font (TTFFont. "Consolas" 12)
                                        ;:else-font "/home/santos/Downloads/cour.ttf"
                                        ;:else-font (TTFFont. "/home/santos/Downloads/cour.ttf" 12)
                                        ;:else-font (TTFFont. "Monospaced" 12)
                                        ;:else-font (TTFFont. "/home/santos/src/robinson/fonts/Boxy/Boxy.ttf" 12)
                                        ;:else-font (CP437Font. "http://dwarffortresswiki.org/images/2/29/Potash_8x8.png" :green 2)
                                        ;:else-font (CP437Font. "http://dwarffortresswiki.org/images/2/29/Potash_8x8.png" :green 2)
                                        ;:else-font (CP437Font. "http://dwarffortresswiki.org/images/b/b7/Kein_400x125.png" :green 2)
                                        ;:else-font (CP437Font. "http://dwarffortresswiki.org/images/0/03/Alloy_curses_12x12.png" :green 2)
                                        :else-font (CP437Font. "http://dwarffortresswiki.org/images/b/be/Pastiche_8x8.png" :green 1)
                                        ;:else-font (CP437Font. "/home/santos/Pictures/LN_EGA8x8.png" :green 2)
                                        :antialias true
                                        ;:fullscreen true
                                        :icon-paths ["images/icon-16x16.png"
                                                     "images/icon-32x32.png"
                                                     "images/icon-128x128.png"]
                                        :fx-shader {:name     "retro.fs"
                                                    :uniforms [["time" @time]
                                                               ["noise" @noise]
                                                               ["colorShift" @colorShift]
                                                               ["scanlineDepth" @scanlineDepth]
                                                               ["brightness" @brightness]
                                                               ["contrast" @contrast]]}})
        fullscreen-sizes (zat/fullscreen-sizes terminal)
        last-key    (atom nil)
        ;; Every 10ms, set the "Rainbow" text to have a random fg color
        fx-chan     (go-loop []
                      (dosync
                        (doseq [x (range (count "Rainbow"))
                                :let [rgb (hsv->rgb (double (rand 360)) 1.0 1.0)]]
                            (zat/set-fx-fg! terminal :rainbow (inc x) 1 rgb)))
                        (zat/assoc-fx-uniform! terminal "time" (swap! time inc))
                        (zat/refresh! terminal)
                      (Thread/sleep 10)
                      (recur))
        ;; Every 33ms, draw a full frame
        render-chan (go-loop []
                      (dosync
                        (let [key-in (or @last-key \?)]
                          (zat/clear! terminal)
                          (zutil/put-string terminal :text 0 0 "Hello world")
                          (doseq [[i c] (take 23 (map-indexed (fn [i c] [i (char c)]) (range (int \a) (int \z))))]
                            (zutil/put-string terminal :text 0 (inc i) (str c) [128 (* 10 i) 0] [0 0 50]))
                          (zutil/put-string terminal :text 12 0 (str key-in))
                          (zutil/put-string terminal :rainbow 1 1 "Rainbow")
                          (zat/refresh! terminal)))
                          ;; ~30fps
                        (Thread/sleep 33)
                        (recur))]
    (log/info "Fullscreen sizes" fullscreen-sizes)
    ;; get key presses in fg thread
    (loop []
      (let [new-key (async/<!! (zat/get-key-chan terminal))]
        (reset! last-key new-key)
        (log/info "got key" (or (str @last-key) "nil"))
        ;; change font size on s/m/l keypress
        (case new-key
          \s (zat/apply-font! terminal
               (CP437Font. "http://dwarffortresswiki.org/images/b/be/Pastiche_8x8.png" :green 1)
               (CP437Font. "http://dwarffortresswiki.org/images/b/be/Pastiche_8x8.png" :green 1))
          \m (zat/apply-font! terminal
               (CP437Font. "http://dwarffortresswiki.org/images/b/be/Pastiche_8x8.png" :green 2)
               (CP437Font. "http://dwarffortresswiki.org/images/b/be/Pastiche_8x8.png" :green 2))
          \l (zat/apply-font! terminal
               (CP437Font. "http://dwarffortresswiki.org/images/b/be/Pastiche_8x8.png" :green 3)
               (CP437Font. "http://dwarffortresswiki.org/images/b/be/Pastiche_8x8.png" :green 3))
          \f (zat/fullscreen! terminal (first fullscreen-sizes))
          \w (zat/fullscreen! terminal false)
          \0 (zat/assoc-fx-uniform! terminal "brightness" (swap! brightness #(- % 0.02)))
          \1 (zat/assoc-fx-uniform! terminal "brightness" (swap! brightness #(+ % 0.02)))
          \2 (do (swap! contrast #(- % 0.02))
                 (log/info "contrast" @contrast)
                 (zat/assoc-fx-uniform! terminal "contrast" @contrast))
          \3 (zat/assoc-fx-uniform! terminal "contrast" (swap! contrast #(+ % 0.02)))
          \4 (zat/assoc-fx-uniform! terminal "scanlineDepth" (swap! scanlineDepth #(- % 0.02)))
          \5 (zat/assoc-fx-uniform! terminal "scanlineDepth" (swap! scanlineDepth #(+ % 0.02)))
          \6 (zat/assoc-fx-uniform! terminal "colorShift" (swap! colorShift #(- % 0.0001)))
          \7 (zat/assoc-fx-uniform! terminal "colorShift" (swap! colorShift #(+ % 0.0001)))
          \8 (zat/assoc-fx-uniform! terminal "noise" (swap! noise #(- % 0.0001)))
          \9 (zat/assoc-fx-uniform! terminal "noise" (swap! noise #(+ % 0.0001)))
          \p (log/info "brightness" @brightness
                       "contrast" @contrast
                       "scanlineDepth" @scanlineDepth
                       "colorShift" @colorShift
                       "noise" @noise
                       "time" @time)
          \q (zat/destroy! terminal)
          nil)
        (if (= new-key :exit)
          (do
            (async/close! fx-chan)
            (async/close! render-chan)
            (System/exit 0))
          (recur))))))


