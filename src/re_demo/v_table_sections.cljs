(ns re-demo.v-table-sections
  (:require [re-com.core   :refer [h-box gap v-box box v-table p label]]
            [re-com.util    :refer [px]]
            [re-demo.utils :refer [title3]]
            [reagent.core  :as reagent]))



(defn render-two-lines
  [{:keys [name section background height width]}]
  [v-box
   :height (str height "px")
   :width   (if width (px width) "1 0 auto")  
   :style  {:color "white" :background-color background :padding "5px"}
   :align  :center
   :justify :center
   :children [[label :label section :style {:font-size 20}]
              [label :label name :style {:font-size 10}]]])


(defn sections-render
  []
  (let [light-blue                "#C7AFE7"
        medium-blue               "#60A0D8"
        blue                      "#0070C4"
        single-dummy-row          (reagent/atom [{:id 1}]) ;; TODO: Changed to atom for testing validation-fn, can change back when we successfully allow atom OR value

        row-height                200
        ;; only one fat row in this table
        width-of-main-row-content (int (/ row-height 0.618 0.618))          ;; fibonachi ratios to make it look pretty
        size2                     (int (* row-height 0.618 0.618))]   ;; fibonachi ratios to make it look pretty
    (fn []
      [v-table
        :model              single-dummy-row
        :row-height         row-height
        :row-content-width  width-of-main-row-content
       
       
        :max-row-viewport-height (- row-height size2)   ;; deliberately create a vertical scrollbar, by not giving enough vertical space to ender the one row

        :row-header-renderer    (fn [row-index, row] [render-two-lines {:name "row header" :section "2" :background medium-blue :height row-height :width size2}])
        :row-footer-renderer    (fn [row-index, row] [render-two-lines {:name "row footer" :section "8" :background medium-blue :height row-height :width size2}])

        ;; column header - section 4
        :column-header-height   size2
        :column-header-renderer (fn [] [render-two-lines {:name "column headers" :section "4" :background medium-blue :height size2 :width width-of-main-row-content}])

        ;; column footer - section 5
        :column-footer-height   size2
        :column-footer-renderer (fn [] [render-two-lines {:name "column footers" :section"6" :background medium-blue :height size2 :width width-of-main-row-content}])

        ;; corners
        :top-left-renderer     (fn [] [render-two-lines {:name "top left"     :section "1" :background blue :height size2 :width size2}])
        :bottom-left-renderer  (fn [] [render-two-lines {:name "bottom left"  :section "3" :background blue :height size2 :width size2}])
        :bottom-right-renderer (fn [] [render-two-lines {:name "bottom right" :section "9" :background blue :height size2 :width size2}])
        :top-right-renderer    (fn [] [render-two-lines {:name "top right"    :section "7" :background blue :height size2 :width size2}])

        :row-renderer          (fn [row_index, row] [render-two-lines {:name "row section" :section "5" :background light-blue :height row-height :width width-of-main-row-content}])])))


;; MT's Notes: 
;; 
;; With the :row-renderer above, i had to passin the height as "width-of-main-row-content"   ??????
;; On section width:
;;   - the width of left sections 1,2,3 is determined by the widest hiccup returned by the 3 renderers for these sections. 
;;   - the width of center  sections 4,5,6 is determined by `:row-content-width`
;;   - the width of left sections 7,8,9 is determined by by the widest hiccup returned by the 3 renderers for these sections.
;; 
;; the viewport width for 4,5,6 is determined by the widest hiccup returned by renderers.  Once I put in an `h-box` it expanded out. When i only had `div` the viewport collapsed to the size of the content.
;; puzzled about column headings XXX
;; 
;; For `:row-viewport-width` the docs say if not specified will take up all available space but this is not 
;; correct. 
;; 
;; I have to provide `:column-header-height`. Could the height of top sections 1, 4, 7 should provide the height. 
;; 
;; Mention in docs that you are likely to use h-box and v-box in renderers.
;; 
;; Discuss with Gregg and Isaac:
;;   - the idea of variable row heights. 
;;   - performance: we have to reduce the amount of inline styles
;;   - How do I create "CSS classes" in a namespace

