(ns re-demo.utils
  (:require [re-com.core :refer [h-box v-box gap title line label hyperlink-href align-style]]))

(defn github-hyperlink
  "given a label and a relative path, return a component which hyperlinks to the GitHub URL in a new tab"
  [label src-path]
  (let [base-url (str "https://github.com/day8/re-com/tree/master/")]
    [hyperlink-href
     :label  label
     ;:style  {:font-size    "13px"}
     :href   (str base-url src-path)
     :target "_blank"]))

(defn source-reference
  [description src]
  [h-box
   :class "all-small-caps"
   :gap    "7px"
   :align  :center
   :children [[label :label "source:"]
              [github-hyperlink description src]]])


(defn panel-title
  "Shown across the top of each page"
  [panel-name src1 src2]
  [v-box
   :children [[h-box
               :margin "0px 0px 9px 0px"
               :height "54px"
               :align :end
               :children [[title
                           :label         panel-name
                           :level         :level1
                           :margin-bottom "0px"
                           :margin-top    "2px"]
                          [gap :size "25px"]
                          (when src1 [h-box
                                      :class "all-small-caps"
                                      :gap    "7px"
                                      :align  :center
                                      :children [[label :label "source:"]
                                                 [github-hyperlink "component" src1]
                                                 [label :label "|"  :style {:font-size "12px"}]
                                                 [github-hyperlink "page" src2]]])]]
              [line]]])

(defn title2
  "2nd level title"
  [text style]
  [title
   :label text
   :level :level2
   :style style])

(defn title3
  "3rd level title"
  [text style]
  [title
   :label text
   :level :level3
   :style style])

(defn status-text
  "given some status text, return a component that displays that status"
  [status style]
  [:span
   [:span.bold "Status: "]
   [:span {:style style} status]])

(defn material-design-hyperlink
  [text]
  [hyperlink-href
   :label  text
   :href   "http://zavoloklom.github.io/material-design-iconic-font/icons.html"
   :target "_blank"])



(defn arg-row
  "I show one argument in an args table (which is itself a horizontal list of arguments)."
  [name-column-width arg odd-row?]
  (let [required   (:required arg)
        default    (:default arg)
        arg-type   (:type arg)
        needed-vec (if (not required)
                     (if (nil? default)
                       [[:span.semibold.all-small-caps "optional"]]
                       [[:span.semibold.all-small-caps "default:"] 
                        [:code {:style {:margin-right 0}} (str default)]])
                     [[:span.semibold.all-small-caps "required"]])]
    [h-box
     :style    {:background (if odd-row? "#F4F4F4" "#FCFCFC") 
                :border-left (when (not odd-row?) "1px solid #f4f4f4")
                :border-right (when (not odd-row?) "1px solid #f4f4f4")}
     :children [[:span {:class "semibold"
                        :style (merge (align-style :align-self :center)
                                      {:width        name-column-width
                                       :padding-left "15px"})}
                 (str (:name arg))]
                 [line :size "1px" :color (if odd-row? "white" "#f4f4f4")]
                 [v-box
                  :style {:padding "7px 15px 2px 15px"}
                  :gap  "4px"
                  :size  "1 1 0px"    ;; grow horizontally to fill the space
                  :children [[h-box
                              :gap   "4px"
                              :children (concat [[:span.semibold  arg-type]
                                                 [gap :size "1"]]
                                                 needed-vec)]
                             [line]
                             [:p
                              ; {:font-size "smaller" :color "red"}
                              (:description arg)]]]]]))

(defn args-table
  "I render component arguments in an easy to read format"
  [args  {:keys [total-width name-column-width] 
          :or   {name-column-width "130px" }}]
  (fn
    []
    ;(println total-width)
    [v-box
     :width    total-width
     :children (concat
                [[title2 "Parameters"]
                 [gap :size "10px"]]
                (map (partial arg-row name-column-width)  args (cycle [true false])))]))


(defn scroll-to-top
  [element]
  (set! (.-scrollTop element) 0))
