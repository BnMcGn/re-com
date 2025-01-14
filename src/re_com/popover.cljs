(ns re-com.popover
  (:require-macros
   [re-com.core         :refer [handler-fn at reflect-current-component]]
   [re-com.validate     :refer [validate-args-macro]])
  (:require
   [re-com.config       :refer [include-args-desc?]]
   [re-com.debug        :refer [->attr]]
   [re-com.util         :refer [get-element-by-id px deref-or-value sum-scroll-offsets merge-css add-map-to-hiccup-call flatten-attr]]
   [re-com.box          :refer [box h-box v-box flex-child-style flex-flow-style align-style]]
   [re-com.close-button :refer [close-button]]
   [re-com.validate     :refer [position? position-options-list popover-status-type? popover-status-types-list number-or-string?
                                string-or-hiccup? string-or-atom? vector-of-maps? css-style? html-attr? parts?]]
   [clojure.string      :as    string]
   [react               :as    react]
   [reagent.core        :as    reagent]
   [reagent.ratom       :refer-macros [reaction]]))

(defn point
  [x y]
  (str x "," y " "))

(defn- split-keyword
  "Return the vector of the two keywords formed by splitting another keyword 'kw' on an internal delimiter (usually '-')
   (split-keyword  :above-left  \"-\") => [:above :left]"
  [kw delimiter]
  (let [keywords (string/split (str kw) (re-pattern (str "[" delimiter ":]")))]
    [(keyword (keywords 1)) (keyword (keywords 2))]))

(defn- calc-popover-pos
  "Determine values for :left :right :top :bottom CSS styles.
   - pop-orient    What side of the anchor the popover will be attached to. One of :above :below :left :right
   - p-width       The px width of the popover after it has been rendered
   - p-height      The px height of the popover after it has been rendered
   - pop-offset    The number of pixels the popover is offset from it's natural position in relation to the popover-arrow (ugh, hard to explain)
   - arrow-length  The px length of the arrow (from the point to middle of arrow base)
   - arrow-gap     The px distance between the anchor and the arrow tip. Positive numbers push the popover away from the anchor
  "
  [pop-orient p-width p-height pop-offset arrow-length arrow-gap]
  (let [total-offset   (+ arrow-length arrow-gap)
        popover-left   (case pop-orient
                         :left           "initial" ;; TODO: Ultimately remove this (must have NO :left which is in Bootstrap .popover class)
                         :right          (px total-offset)
                         (:above :below) (px (or pop-offset (/ p-width 2)) :negative))
        popover-top    (case pop-orient
                         (:left :right)  (px (or pop-offset (/ p-height 2)) :negative)
                         :above          "initial"
                         :below          (px total-offset))
        popover-right  (case pop-orient
                         :left           (px total-offset)
                         :right          nil
                         :above          nil
                         :below          nil)
        popover-bottom (case pop-orient
                         :left           nil
                         :right          nil
                         :above          (px total-offset)
                         :below          nil)]
    {:left popover-left :top popover-top :right popover-right :bottom popover-bottom}))

(defn calculate-optimal-position
  "Calculate the optimal :position value that results in the least amount of clipping by the screen edges
  Taken from: https://github.com/Lambda-X/cljs-repl-web/blob/0.3.1/src/cljs/cljs_repl_web/views/utils.cljs#L52
  Thanks to @richiardiandrea and @tomek for this code"
  [[x y]]
  (let [w (.-innerWidth   js/window) ;; Width/height of the browser window viewport including, if rendered, the vertical scrollbar
        h (.-innerHeight  js/window)
        h-threshold-left  (quot w 3)
        h-threshold-cent  (* 2 h-threshold-left)
        h-position        (cond
                            (< x h-threshold-left) "right"
                            (< x h-threshold-cent) "center"
                            :else "left")
        v-threshold       (quot h 2)
        v-position        (if (< y v-threshold) "below" "above")]
    (keyword (str v-position \- h-position))))

(defn calc-element-midpoint
  "Given a node reference, calculate the absolute x and y coordinates of the node's midpoint"
  [node]
  (let [bounding-rect (.getBoundingClientRect node)]
    [(/ (+ (.-right  bounding-rect) (.-left bounding-rect)) 2)    ;; x
     (/ (+ (.-bottom bounding-rect) (.-top  bounding-rect)) 2)])) ;; y

(def popover-arrow-css-spec
  {:arrow {:class ["popover-arrow" "rc-popover-arrow"]
           :style (fn [{:keys [orientation pop-offset arrow-length arrow-width]}]
                    {:position "absolute"
                     (case orientation ;; Connect arrow to edge of popover
                       :left  :right
                       :right :left
                       :above :bottom
                       :below :top) (px arrow-length :negative)

                     (case orientation ;; Position the arrow at the top/left, center or bottom/right of the popover
                       (:left  :right) :top
                       (:above :below) :left) (if (nil? pop-offset) "50%" (px pop-offset))

                     (case orientation ;; Adjust the arrow position so it's center is attached to the desired position set above
                       (:left  :right) :margin-top
                       (:above :below) :margin-left) (px (/ arrow-width 2) :negative)

                     :width (px (case orientation ;; Arrow is rendered in a rectangle so choose the correct edge length
                                  (:left  :right) arrow-length
                                  (:above :below) arrow-width))

                     :height (px (case orientation ;; Same as :width comment above
                                   (:left  :right) arrow-width
                                   (:above :below) arrow-length))})}
   :arrow-drawing {:style
                   (fn [{:keys [popover-color grey-arrow? popover-border-color no-border?]}]
                     {:fill (if popover-color
                              popover-color
                              (if grey-arrow? "#f7f7f7" "white"))
                      :stroke (or popover-border-color (when-not no-border? "rgba(0, 0, 0, .2)"))
                      :stroke-width "1"})}})

(defn popover-arrow
  "Render the triangle which connects the popover to the anchor (using SVG)"
  [& {:keys [orientation pop-offset arrow-length arrow-width grey-arrow? no-border? popover-color popover-border-color parts]}]
  (let [half-arrow-width (/ arrow-width 2)
        arrow-shape {:left  (str (point 0 0)            (point arrow-length half-arrow-width) (point 0 arrow-width))
                     :right (str (point arrow-length 0) (point 0 half-arrow-width)            (point arrow-length arrow-width))
                     :above (str (point 0 0)            (point half-arrow-width arrow-length) (point arrow-width 0))
                     :below (str (point 0 arrow-length) (point half-arrow-width 0)            (point arrow-width arrow-length))}
        cmerger (merge-css popover-arrow-css-spec {:parts parts})]
    [:svg
     (cmerger :arrow {:orientation orientation :pop-offset pop-offset :arrow-length arrow-length :arrow-width arrow-width})
     [:polyline (merge {:points (arrow-shape orientation)}
                       (cmerger :arrow-drawing {:popover-color popover-color
                                                :grey-arrow? grey-arrow?
                                                :popover-border-color popover-border-color
                                                :no-border? no-border?}))]]))

;;--------------------------------------------------------------------------------------------------
;; Component: backdrop
;;--------------------------------------------------------------------------------------------------

(def backdrop-args-desc
  (when include-args-desc?
    [{:name :opacity  :required false :default 0.0 :type "double | string" :validate-fn number-or-string? :description [:span "opacity of backdrop from:" [:br] "0.0 (transparent) to 1.0 (opaque)"]}
     {:name :on-click :required false              :type "-> nil"          :validate-fn fn?               :description "a function which takes no params and returns nothing. Called when the backdrop is clicked"}
     {:name :class    :required false              :type "string"          :validate-fn string?           :description "CSS class names, space separated"}
     {:name :style    :required false              :type "CSS style map"   :validate-fn css-style?        :description "override component style(s) with a style map, only use in case of emergency (applies to the outer container)"}
     {:name :attr     :required false              :type "HTML attr map"   :validate-fn html-attr?        :description [:span "HTML attributes, like " [:code ":on-mouse-move"] [:br] "No " [:code ":class"] " or " [:code ":style"] "allowed (applies to the outer container)"]}
     {:name :src      :required false              :type "map"             :validate-fn map?              :description [:span "Used in dev builds to assist with debugging. Source code coordinates map containing keys" [:code ":file"] "and" [:code ":line"]  ". See 'Debugging'."]}
     {:name :debug-as :required false              :type "map"             :validate-fn map?              :description [:span "Used in dev builds to assist with debugging, when one component is used implement another component, and we want the implementation component to masquerade as the original component in debug output, such as component stacks. A map optionally containing keys" [:code ":component"] "and" [:code ":args"] "."]}]))

(def backdrop-css-spec
  {:main {:class ["noselect" "rc-backdrop"]
          :style (fn [{:keys [opacity]}]
                   {:position         "fixed"
                    :left             "0px"
                    :top              "0px"
                    :width            "100%"
                    :height           "100%"
                    :background-color "black"
                    :opacity          (or opacity 0.0)})}})

(defn backdrop
  "Renders a backdrop div which fills the entire page and responds to clicks on it. Can also specify how tranparent it should be"
  [& {:keys [opacity on-click class style attr] :as args}]
  (or
   (validate-args-macro backdrop-args-desc args)
   (let [cmerger (merge-css backdrop-css-spec args)]
     [:div
      (merge
       (flatten-attr
        (cmerger :main
                 {:attr {:on-click (handler-fn (on-click))}}))
       (->attr args))])))

;;--------------------------------------------------------------------------------------------------
;; Component: popover-title
;;--------------------------------------------------------------------------------------------------

(def popover-title-parts-desc
  (when include-args-desc?
    [{:type :legacy       :level 0 :class "rc-popover-title" :impl "[:h3]"          :notes "Outer wrapper of the popover title."}
     {:name :container    :level 1 :class ""                 :impl "[h-box]"        :notes [:span "Container for the " [:code ":title"] " and the close button."]}
     {:name :close-button :level 2 :class ""                 :impl "[close-button]" :notes "The close button."}]))

(def popover-title-css-spec
  {:main {:class ["popover-title" "rc-popover-title"]
          :style (merge (flex-child-style "inherit")
                        {:font-size "18px"})}
   :container {}
   :close-button {}})

(def popover-title-parts
  (when include-args-desc?
    (-> (map :name popover-title-parts-desc) set)))

(def popover-title-args-desc
  (when include-args-desc?
    [{:name :showing?       :required true                 :type "boolean r/atom"                                              :description "an r/atom. When the value is true, the popover shows."}
     {:name :title          :required false                :type "string | hiccup"   :validate-fn string-or-hiccup?            :description "describes the title of the popover. Default font size is 18px to make it stand out"}
     {:name :close-button?  :required false  :default true :type "boolean"                                                     :description "when true, displays the close button"}
     {:name :close-callback :required false                :type "-> nil"            :validate-fn fn?                          :description [:span "a function which takes no params and returns nothing. Called when the close button is pressed. Not required if " [:code ":showing?"] " atom passed in OR " [:code ":close-button?"] " is set to false"]}
     {:name :class          :required false                :type "string"            :validate-fn string?                      :description "CSS class names, space separated"}
     {:name :style          :required false                :type "CSS style map"     :validate-fn css-style?                   :description "override component style(s) with a style map, only use in case of emergency (applies to the outer container)"}
     {:name :attr           :required false                :type "HTML attr map"     :validate-fn html-attr?                   :description [:span "HTML attributes, like " [:code ":on-mouse-move"] [:br] "No " [:code ":class"] " or " [:code ":style"] "allowed (applies to the outer container)"]}
     {:name :parts          :required false                :type "map"               :validate-fn (parts? popover-title-parts) :description "See Parts section below."}
     {:name :src            :required false                :type "map"               :validate-fn map?                         :description [:span "Used in dev builds to assist with debugging. Source code coordinates map containing keys" [:code ":file"] "and" [:code ":line"]  ". See 'Debugging'."]}
     {:name :debug-as       :required false                :type "map"               :validate-fn map?                         :description [:span "Used in dev builds to assist with debugging, when one component is used implement another component, and we want the implementation component to masquerade as the original component in debug output, such as component stacks. A map optionally containing keys" [:code ":component"] "and" [:code ":args"] "."]}]))

(defn- popover-title
  "Renders a title at the top of a popover with an optional close button on the far right"
  [& {:keys [showing? title close-button? close-callback class style attr parts]
      :as args}]
  (or
   (validate-args-macro popover-title-args-desc args)
   #_(assert (or ((complement nil?) showing?) ((complement nil?) close-callback)) "Must specify either showing? OR close-callback") ;; IJ: TODO re-refactor
   (let [close-button? (if (nil? close-button?) true close-button?)
         cmerger (merge-css popover-title-css-spec args)]
     [:h3
      (merge
       (flatten-attr (cmerger :main))
       (->attr args))
      (add-map-to-hiccup-call
       (cmerger :container)
       [h-box
        :src      (at)
        :justify  :between
        :align    :center
        :children [title
                   (when close-button?
                     (add-map-to-hiccup-call
                      (cmerger :close-button)
                      [close-button
                       :src         (at)
                       :on-click    #(if close-callback
                                       (close-callback)
                                       (reset! showing? false))
                       :div-size    0
                       :font-size   26
                       :top-offset  -1
                       :left-offset -5]))]])])))

;;--------------------------------------------------------------------------------------------------
;; Component: popover-border
;;--------------------------------------------------------------------------------------------------

(defn next-even-integer
  [num]
  (-> num inc (/ 2) int (* 2)))

(defn calc-pop-offset
  [arrow-pos position-offset p-width p-height]
  (case arrow-pos
    :center nil
    :right  (+ 20 position-offset)
    :below  (+ 20 position-offset)
    :left   (if p-width (- (- p-width 25) position-offset) p-width)
    :above  (if p-height (- (- p-height 25) position-offset) p-height)))

(defn popover-clipping
  [node]
  (let [viewport-width  (.-innerWidth   js/window)    ;; Width  (in pixels) of the browser window viewport including, if rendered, the vertical   scrollbar.
        viewport-height (.-innerHeight  js/window)    ;; Height (in pixels) of the browser window viewport including, if rendered, the horizontal scrollbar.
        bounding-rect   (.getBoundingClientRect node)
        left            (.-left   bounding-rect)
        right           (.-right  bounding-rect)
        top             (.-top    bounding-rect)
        bottom          (.-bottom bounding-rect)
        clip-left       (when (< left 0) (- left))
        clip-right      (when (> right viewport-width) (- right viewport-width))
        clip-top        (when (< top 0) (- top))
        clip-bottom     (when (> bottom viewport-height) (- bottom viewport-height))]
    #_(when (or (some? clip-left) (some? clip-right) (some? clip-top) (some? clip-bottom))  ;; Return full clipping details (or nil if not clipped)
        {:left clip-left :right clip-right :top clip-top :bottom clip-bottom})
    (or (some? clip-left) (some? clip-right) (some? clip-top) (some? clip-bottom))))        ;; Return boolean

(def popover-border-parts-desc
  (when include-args-desc?
    [{:type :legacy       :level 0 :class "rc-popover-border"  :impl "[:div]"  :notes "Outer wrapper of the popover title."}
     {:name :arrow        :level 1 :class "rc-popover-arrow"   :impl "[:svg]"  :notes ""}
     {:name :content      :level 2 :class "rc-popover-content" :impl "[:div]"  :notes ""}]))

(def popover-border-css-spec
  {:main {:class ["popover" "fade" "in" "rc-popover-border"]
          :style (fn [{:keys [top left width height background-color border-color tooltip-style?
                              orientation margin-left margin-top ready-to-show? right bottom]
                       :as params}]
                   (merge
                    (into {}
                          (for [k [:top :left :width :height :background-color :border-color
                                   :margin-left :margin-top :right :bottom]
                                :let [v (get params k)]
                                :when v]
                            [k v]))
                    (when tooltip-style?
                      {:border-radius "4px"
                       :box-shadow "none"
                       :border "none"})

                    ;; The popover point is zero width, therefore its absolute children will consider this width when deciding their
                    ;; natural size and in particular, how they natually wrap text. The right hand side of the popover is used as a
                    ;; text wrapping point so it will wrap, depending on where the child is positioned. The margin is also taken into
                    ;; consideration for this point so below, we set the margins to negative a lot to prevent
                    ;; this annoying wrapping phenomenon.
                    (case orientation
                      :left {:margin-left "-2000px"}
                      (:right :above :below) {:margin-right "-2000px"})

                    ;; make it visible and turn off Bootstrap max-width and remove Bootstrap padding which adds an internal white border
                    {:display "block"
                     :opacity (if ready-to-show? "1" "0")
                     :max-width "none"
                     :padding "0px"}))}
   :content {:class ["popover-content" "rc-popover-content"]
             :style (fn [{:keys [padding]}]
                      {:padding padding})}})

(def popover-border-parts
  (when include-args-desc?
    (-> (map :name popover-border-parts-desc) set)))

(def popover-border-args-desc
  (when include-args-desc?
    [{:name :children             :required true                        :type "vector"           :validate-fn sequential?                   :description "a vector of component markups"}
     {:name :position             :required true                        :type "keyword r/atom"   :validate-fn position?                     :description [:span "relative to this anchor. One of " position-options-list]}
     {:name :position-offset      :required false                       :type "integer"          :validate-fn number?                       :description [:span "px offset of the arrow from its default " [:code ":position"] " along the popover border. Is ignored when " [:code ":position"] " is one of the " [:code ":xxx-center"] " variants. Positive numbers slide the popover toward its center"]}
     {:name :width                :required false                       :type "string"           :validate-fn string?                       :description "a CSS style describing the popover width"}
     {:name :height               :required false :default "auto"       :type "string"           :validate-fn string?                       :description "a CSS style describing the popover height"}
     {:name :popover-color        :required false :default "white"      :type "string"           :validate-fn string?                       :description "fill color of the popover"}
     {:name :popover-border-color :required false                       :type "string"           :validate-fn string?                       :description "color of the popover border, including the arrow"}
     {:name :arrow-length         :required false :default 11           :type "integer | string" :validate-fn number-or-string?             :description "the length in pixels of the arrow (from pointy part to middle of arrow base)"}
     {:name :arrow-width          :required false :default 22           :type "integer | string" :validate-fn number-or-string?             :description "the width in pixels of arrow base"}
     {:name :arrow-gap            :required false :default -1           :type "integer"          :validate-fn number?                       :description "px gap between the anchor and the arrow tip. Positive numbers push the popover away from the anchor"}
     {:name :padding              :required false                       :type "string"           :validate-fn string?                       :description "a CSS style which overrides the inner padding of the popover"}
     {:name :margin-left          :required false                       :type "string"           :validate-fn string?                       :description "a CSS style describing the horiztonal offset from anchor after position"}
     {:name :margin-top           :required false                       :type "string"           :validate-fn string?                       :description "a CSS style describing the vertical offset from anchor after position"}
     {:name :tooltip-style?       :required false :default false        :type "boolean"                                                     :description "setup popover styles for a tooltip"}
     {:name :arrow-renderer       :required false                       :type "-> hiccup"        :validate-fn fn?                           :description "A function that may take any of the keywords received by popover-arrow in popover.cljs. Returns hiccup for the popover arrow."}
     {:name :title                :required false                       :type "string | markup"                                             :description "describes a title"}
     {:name :class                :required false                       :type "string"           :validate-fn string?                       :description "CSS class names, space separated (applies to the outer container)"}
     {:name :style                :required false                       :type "CSS style map"    :validate-fn css-style?                    :description "override component style(s) with a style map, only use in case of emergency (applies to the outer container)"}
     {:name :attr                 :required false                       :type "HTML attr map"    :validate-fn html-attr?                    :description [:span "HTML attributes, like " [:code ":on-mouse-move"] [:br] "No " [:code ":class"] " or " [:code ":style"] "allowed (applies to the outer container)"]}
     {:name :parts                :required false                       :type "map"              :validate-fn (parts? popover-border-parts) :description "See Parts section below."}
     {:name :src                  :required false                       :type "map"              :validate-fn map?                          :description [:span "Used in dev builds to assist with debugging. Source code coordinates map containing keys" [:code ":file"] "and" [:code ":line"]  ". See 'Debugging'."]}
     {:name :debug-as             :required false                       :type "map"              :validate-fn map?                          :description [:span "Used in dev builds to assist with debugging, when one component is used implement another component, and we want the implementation component to masquerade as the original component in debug output, such as component stacks. A map optionally containing keys" [:code ":component"] "and" [:code ":args"] "."]}]))

(defn popover-border
  "Renders an element or control along with a Bootstrap popover"
  [& {:keys [position position-offset title src] :as args}]
  (or
   (validate-args-macro popover-border-args-desc args)
   (let [pop-id                  (gensym "popover-")
         rendered-once           (reagent/atom false)        ;; The initial render is off screen because rendering it in place does not render at final width, and we need to measure it to be able to place it properly
         ready-to-show?          (reagent/atom false)        ;; This is used by the optimal position code to avoid briefly seeing it in its intended position before quickly moving to the optimal position
         p-width                 (reagent/atom 0)
         p-height                (reagent/atom 0)
         pop-offset              (reagent/atom 0)
         found-optimal           (reagent/atom false)
         !pop-border             (atom nil)
         ref!                    (partial reset! !pop-border)
         calc-metrics            (fn [pos]
                                   (let [popover-elem            (get-element-by-id pop-id)
                                         [orientation arrow-pos] (split-keyword pos "-")
                                         grey-arrow?             (and title (or (= orientation :below) (= arrow-pos :below)))]
                                     (reset! p-width    (if popover-elem (next-even-integer (.-clientWidth  popover-elem)) 0)) ;; next-even-integer required to avoid wiggling popovers (width/height appears to prefer being even and toggles without this call)
                                     (reset! p-height   (if popover-elem (next-even-integer (.-clientHeight popover-elem)) 0))
                                     (reset! pop-offset (calc-pop-offset arrow-pos position-offset @p-width @p-height))
                                     [orientation grey-arrow?]))]
     (reagent/create-class
      {:display-name "popover-border"

       :component-did-mount
       (fn []
         (reset! rendered-once true))

       :component-did-update
       (fn [this]
         (let [clipped?        (popover-clipping @!pop-border)
               anchor-node     (-> @!pop-border .-parentNode .-parentNode .-parentNode)] ;; Get reference to rc-point-wrapper node
           (when (and clipped? (not @found-optimal))
             (reset! position (calculate-optimal-position (calc-element-midpoint anchor-node)))
             (reset! found-optimal true))
           (calc-metrics @position)
           (reset! ready-to-show? true)))

       :reagent-render
       (fn popover-border-render
         [& {:keys [children position position-offset width height popover-color popover-border-color arrow-length
                    arrow-width arrow-gap padding margin-left margin-top tooltip-style? arrow-renderer
                    title class style attr parts src]
             :or {arrow-length 11 arrow-width 22 arrow-gap -1}
             :as args}]
         (or
          (validate-args-macro popover-border-args-desc args)
          (let [[orientation grey-arrow?] (calc-metrics @position)
                cmerger (merge-css popover-border-css-spec args)]
            [:div
             (merge
              {:id pop-id}
              (flatten-attr
               (cmerger
                :main
                (merge
                 (if @rendered-once
                   (when pop-id (calc-popover-pos orientation @p-width @p-height @pop-offset arrow-length arrow-gap))
                   {:top "-10000px" :left "-10000px"})
                 {:width width :height height :background-color popover-color
                  :border-color popover-border-color :tooltip-style? tooltip-style?
                  :orientation orientation :margin-left margin-left :margin-top margin-top
                  :ready-to-show? @ready-to-show?})))
              (->attr args)
              {:ref ref!})
             [(or arrow-renderer popover-arrow)
              :orientation orientation
              :pop-offset @pop-offset
              :arrow-length arrow-length
              :arrow-width arrow-width
              :grey-arrow? grey-arrow?
              :tooltip-style? tooltip-style?
              :popover-color popover-color
              :popover-border-color popover-border-color
              :parts parts]
             (when title title)
             (into [:div
                    (flatten-attr
                     (cmerger :content {:padding padding}))]
                   children)])))}))))

;;--------------------------------------------------------------------------------------------------
;; Component: popover-content-wrapper
;;--------------------------------------------------------------------------------------------------

(def popover-content-wrapper-parts-desc
  (when include-args-desc?
    [{:type :legacy   :level 0 :class "rc-popover-content-wrapper" :impl "[:div]" :notes ""}
     {:name :backdrop :level 1 :class "rc-point-wrapper"           :impl "[backdrop]" :notes ""}
     {:name :border   :level 2 :class ""                           :impl "[popover-border]" :notes ""}
     {:name :title    :level 3 :class ""                           :impl "[popover-title]" :notes ""}]))

(def popover-content-wrapper-css-spec
  {:main {:class ["popover-content-wrapper" "rc-popover-content-wrapper"]
          :style (fn [{:keys [no-clip? left-offset top-offset]}]
                   (merge (flex-child-style "inherit")
                          (when no-clip?
                            {:position "fixed"
                             :left (px left-offset)
                             :top (px top-offset)})))}
   :backdrop {}
   :border {}
   :title {}})

(def popover-content-wrapper-parts
  (when include-args-desc?
    (-> (map :name popover-content-wrapper-parts-desc) set)))

(def popover-content-wrapper-args-desc
  (when include-args-desc?
    [{:name :showing-injected?    :required true                         :type "boolean r/atom"                                  :description [:span "an atom or value. When the value is true, the popover shows." [:br] [:strong "NOTE: "] "When used as direct " [:code ":popover"] " arg in popover-anchor-wrapper, this arg will be injected automatically by popover-anchor-wrapper. If using your own popover function, you must add this yourself"]}
     {:name :position-injected    :required true                         :type "keyword r/atom"   :validate-fn position?         :description [:span "relative to this anchor. One of " position-options-list [:br] [:strong "NOTE: "] "See above NOTE for " [:code ":showing-injected?"] ". Same applies"]}
     {:name :position-offset      :required false                        :type "integer"          :validate-fn number?           :description [:span "px offset of the arrow from its default " [:code ":position"] " along the popover border. Is ignored when " [:code ":position"] " is one of the " [:code ":xxx-center"] " variants. Positive numbers slide the popover toward its center"]}
     {:name :no-clip?             :required false  :default false        :type "boolean"                                         :description "when an anchor is in a scrolling region (e.g. scroller component), the popover can sometimes be clipped. By passing true for this parameter, re-com will use a different CSS method to show the popover. This method is slightly inferior because the popover can't track the anchor if it is repositioned"}
     {:name :width                :required false                        :type "string"           :validate-fn string?           :description "a CSS style representing the popover width"}
     {:name :height               :required false                        :type "string"           :validate-fn string?           :description "a CSS style representing the popover height"}
     {:name :backdrop-opacity     :required false  :default 0.0          :type "double | string"  :validate-fn number-or-string? :description "indicates the opacity of the backdrop where 0.0=transparent, 1.0=opaque"}
     {:name :on-cancel            :required false                        :type "-> nil"           :validate-fn fn?               :description "a function which takes no params and returns nothing. Called when the popover is cancelled (e.g. user clicks away)"}
     {:name :title                :required false                        :type "string | hiccup"  :validate-fn string-or-hiccup? :description "describes the title of the popover. The default font size is 18px to make it stand out"}
     {:name :close-button?        :required false  :default true         :type "boolean"                                         :description "when true, displays the close button"}
     {:name :body                 :required false                        :type "string | hiccup"  :validate-fn string-or-hiccup? :description "describes the popover body. Must be a single component"}
     {:name :tooltip-style?       :required false  :default false        :type "boolean"                                         :description "setup popover styles for a tooltip"}
     {:name :arrow-renderer       :required false                       :type "-> hiccup"        :validate-fn fn?                           :description "A function that may take any of the keywords received by popover-arrow in popover.cljs. Returns hiccup for the popover arrow."}
     {:name :popover-color        :required false  :default "white"      :type "string"           :validate-fn string?           :description "fill color of the popover"}
     {:name :popover-border-color :required false                        :type "string"           :validate-fn string?           :description "color of the popover border, including the arrow"}
     {:name :arrow-length         :required false  :default 11           :type "integer | string" :validate-fn number-or-string? :description "the length in pixels of the arrow (from pointy part to middle of arrow base)"}
     {:name :arrow-width          :required false  :default 22           :type "integer | string" :validate-fn number-or-string? :description "the width in pixels of arrow base"}
     {:name :arrow-gap            :required false  :default -1           :type "integer"          :validate-fn number?           :description "px gap between the anchor and the arrow tip. Positive numbers push the popover away from the anchor"}
     {:name :padding              :required false                        :type "string"           :validate-fn string?           :description "a CSS style which overrides the inner padding of the popover"}
     {:name :class                :required false                        :type "string"           :validate-fn string?           :description "CSS class names, space separated (applies to the outer container)"}
     {:name :style                :required false                        :type "CSS style map"    :validate-fn css-style?        :description "override component style(s) with a style map, only use in case of emergency (applies to the outer container)"}
     {:name :attr                 :required false                        :type "HTML attr map"    :validate-fn html-attr?        :description [:span "HTML attributes, like " [:code ":on-mouse-move"] [:br] "No " [:code ":class"] " or " [:code ":style"] "allowed (applies to the outer container)"]}
     {:name :parts                :required false                        :type "map"              :validate-fn (parts? #{:backdrop :border :title}) :description "See Parts section below."}
     {:name :src                  :required false                        :type "map"              :validate-fn map?              :description [:span "Used in dev builds to assist with debugging. Source code coordinates map containing keys" [:code ":file"] "and" [:code ":line"]  ". See 'Debugging'."]}
     {:name :debug-as             :required false                        :type "map"              :validate-fn map?              :description [:span "Used in dev builds to assist with debugging, when one component is used implement another component, and we want the implementation component to masquerade as the original component in debug output, such as component stacks. A map optionally containing keys" [:code ":component"] "and" [:code ":args"] "."]}]))

(defn popover-content-wrapper
  "Abstracts several components to handle the 90% of cases for general popovers and dialog boxes"
  [& {:keys [no-clip?] :as args}]
  (or
   (validate-args-macro popover-content-wrapper-args-desc args)
   (let [left-offset              (reagent/atom 0)
         top-offset               (reagent/atom 0)
         !ref                     (atom nil)
         ref!                     (partial reset! !ref)
         position-no-clip-popover (fn position-no-clip-popover
                                    [this]
                                    (when no-clip?
                                      (let [popover-point-node (.-parentNode @!ref)                          ;; Get reference to rc-popover-point node
                                            bounding-rect      (.getBoundingClientRect popover-point-node)]  ;; The modern magical way of getting offsetLeft and offsetTop. Returns this: https://developer.mozilla.org/en-US/docs/Mozilla/Tech/XPCOM/Reference/Interface/nsIDOMClientRect
                                        (reset! left-offset (.-left bounding-rect))
                                        (reset! top-offset  (.-top  bounding-rect)))))]
     (reagent/create-class
      {:display-name "popover-content-wrapper"

       :component-did-mount
       (fn [this]
         (position-no-clip-popover this))

       :component-did-update
       (fn [this]
         (position-no-clip-popover this))

       :reagent-render
       (fn popover-content-wrapper-render
         [& {:keys [showing-injected? position-injected position-offset no-clip? width height backdrop-opacity on-cancel
                    title close-button? body tooltip-style? popover-color popover-border-color arrow-length arrow-width
                    arrow-gap arrow-renderer padding class style attr parts]
             :or {arrow-length 11 arrow-width 22 arrow-gap -1}
             :as args}]
         (or
          (validate-args-macro popover-content-wrapper-args-desc args)
          (let [cmerger (merge-css popover-content-wrapper-css-spec args)]
            @position-injected ;; Dereference this atom. Although nothing here needs its value explicitly, the calculation of left-offset and top-offset are affected by it for :no-clip? true
            [:div
             (merge (flatten-attr
                     (cmerger :main {:no-clip? no-clip?
                                     :left-offset @left-offset
                                     :top-offset @top-offset}))
                    (->attr args)
                    {:ref ref!})
             (when (and (deref-or-value showing-injected?)  on-cancel)
               (add-map-to-hiccup-call
                (cmerger :backdrop)
                [backdrop
                 :src      (at)
                 :opacity  backdrop-opacity
                 :on-click on-cancel]))
             (add-map-to-hiccup-call
              (cmerger :border)
              [popover-border
               :src                  (at)
               :position             position-injected
               :position-offset      position-offset
               :width                width
               :height               height
               :tooltip-style?       tooltip-style?
               :arrow-renderer       arrow-renderer
               :popover-color        popover-color
               :popover-border-color popover-border-color
               :arrow-length         arrow-length
               :arrow-width          arrow-width
               :arrow-gap            arrow-gap
               :padding              padding
               :title                (when title (add-map-to-hiccup-call
                                                  (cmerger :title)
                                                  [popover-title
                                                   :src            (at)
                                                   :title          title
                                                   :showing?       showing-injected?
                                                   :close-button?  close-button?
                                                   :close-callback on-cancel]))
               :children             [body]])])))}))))

;;--------------------------------------------------------------------------------------------------
;; Component: popover-anchor-wrapper
;;--------------------------------------------------------------------------------------------------

(def popover-anchor-wrapper-parts-desc
  (when include-args-desc?
    [{:type :legacy        :level 0 :class "rc-popover-anchor-wrapper" :impl "[:div]" :notes "Outer wrapper of the anchor, popover, backdrop, everything."}
     {:name :point-wrapper :level 1 :class "rc-point-wrapper"          :impl "[:div]" :notes "Wraps the anchor component and the popover-point (which the actual popover points to)."}
     {:name :point         :level 2 :class "rc-popover-point"          :impl "[:div]" :notes [:span "The point (width/height 0) which is placed at the center of the relevant side of the anchor, based on " [:code ":position"] "tag"]}]))

(def popover-anchor-wrapper-css-spec
  {:main {:class ["rc-popover-anchor-wrapper" "display-inline-flex"]
          :style (flex-child-style "inherit")}
   :point-wrapper {:class ["display-inline-flex" "rc-point-wrapper"]
                   :style (fn [{:keys [flex-flow]}]
                            (merge (flex-child-style "auto")
                                   (flex-flow-style flex-flow)
                                   (align-style :align-items :center)))}
   :point {:class ["rc-popover-point" "display-inline-flex"]
           :style (merge
                   (flex-child-style "auto")
                   {:position "relative"
                    :z-index 4})}})

(def popover-anchor-wrapper-parts
  (when include-args-desc?
    (-> (map :name popover-anchor-wrapper-parts-desc) set)))

(def popover-anchor-wrapper-args-desc
  (when include-args-desc?
    [{:name :showing? :required true                        :type "boolean r/atom"                                 :description "an atom or value. When the value is true, the popover shows"}
     {:name :position :required true                        :type "keyword"         :validate-fn position?         :description [:span "relative to this anchor. One of " position-options-list]}
     {:name :anchor   :required true                        :type "string | hiccup" :validate-fn string-or-hiccup? :description "the component the popover is attached to"}
     {:name :popover  :required true                        :type "string | hiccup" :validate-fn string-or-hiccup? :description "the popover body component"}
     {:name :class    :required false                       :type "string"          :validate-fn string?           :description "CSS class names, space separated (applies to the outer container)"}
     {:name :style    :required false                       :type "CSS style map"   :validate-fn css-style?        :description "override component style(s) with a style map, only use in case of emergency (applies to the outer container)"}
     {:name :attr     :required false                       :type "HTML attr map"   :validate-fn html-attr?        :description [:span "HTML attributes, like " [:code ":on-mouse-move"] [:br] "No " [:code ":class"] " or " [:code ":style"] "allowed (applies to the outer container)"]}
     {:name :parts    :required false                       :type "map"             :validate-fn (parts? popover-anchor-wrapper-parts) :description "See Parts section below."}
     {:name :src      :required false                       :type "map"             :validate-fn map?              :description [:span "Used in dev builds to assist with debugging. Source code coordinates map containing keys" [:code ":file"] "and" [:code ":line"]  ". See 'Debugging'."]}
     {:name :debug-as :required false                       :type "map"             :validate-fn map?              :description [:span "Used in dev builds to assist with debugging, when one component is used implement another component, and we want the implementation component to masquerade as the original component in debug output, such as component stacks. A map optionally containing keys" [:code ":component"] "and" [:code ":args"] "."]}]))

(defn popover-anchor-wrapper
  "Renders an element or control along with a Bootstrap popover"
  [& {:keys [showing? position src] :as args}]
  (or
   (validate-args-macro popover-anchor-wrapper-args-desc args)
   (let [external-position (reagent/atom position)
         internal-position (reagent/atom @external-position)
         reset-on-hide     (reaction (when-not (deref-or-value showing?)
                                       (reset! internal-position @external-position)))]
     (reagent/create-class
      {:display-name "popover-anchor-wrapper"

       :reagent-render
       (fn popover-anchor-wrapper-render
         [& {:keys [showing? position anchor popover class style attr parts] :as args}]
         (or
          (validate-args-macro popover-anchor-wrapper-args-desc args)
          (do
            @reset-on-hide ;; Dereference this reaction, otherwise it won't be set up. The reaction is set to run whenever the popover closes
            (when (not= @external-position position) ;; Has position changed externally?
              (reset! external-position position)
              (reset! internal-position @external-position))
            (let [[orientation _arrow-pos] (split-keyword @internal-position "-") ;; only need orientation here
                  place-anchor-before?    (case orientation (:left :above) false true)
                  flex-flow               (case orientation (:left :right) "row" "column")
                  cmerger (merge-css popover-anchor-wrapper-css-spec args)]
              [:div
               (merge (flatten-attr (cmerger :main))
                      (->attr args))
               [:div                                ;; Wrapper around the anchor and the "point"
                (flatten-attr
                 (cmerger :point-wrapper {:flex-flow flex-flow}))
                (when place-anchor-before? anchor)
                (when (deref-or-value showing?)
                  [:div                             ;; The "point" that connects the anchor to the popover
                   (flatten-attr (cmerger :point))
                   (into popover [:showing-injected? showing? :position-injected internal-position])]) ;; NOTE: Inject showing? and position to the popover
                (when-not place-anchor-before? anchor)]]))))}))))

;;--------------------------------------------------------------------------------------------------
;; Component: popover-tooltip
;;--------------------------------------------------------------------------------------------------

(def popover-tooltip-parts-desc
  (when include-args-desc?
    [{:type :legacy                 :level 0 :class "rc-popover-anchor-wrapper"                 :impl "[popover-anchor-wrapper]"  :notes "Outer wrapper of the popover tooltip."}
     {:name :content-wrapper        :level 1 :class ""                                          :impl "[popover-content-wrapper]" :notes ""}
     {:name :v-box                  :level 2 :class ""                                          :impl "[v-box]"                   :notes ""}
     {:name :close-button-container :level 3 :class "rc-popover-tooltip-close-button-container" :impl "[box]"                     :notes ""}
     {:name :close-button           :level 4 :class "rc-popover-tooltip-close-button"           :impl "[close-button]"            :notes ""}]))

(def popover-tooltip-css-spec
  {:main {:class ["rc-popover-tooltip"]}
   :content-wrapper {}
   :v-box {:style (fn [{:keys [status]}]
                    (if (= status :info)
                      {:color "white"
                       :font-size "14px"
                       :padding "4px"}
                      {:color "white"
                       :font-size "12px"
                       :font-weight "bold"
                       :text-align "center"}))}
   :close-button-container {:class ["rc-popover-tooltip-close-button-container"]}
   :close-button {:class ["rc-popover-tooltip-close-button"]}})

(def popover-tooltip-parts
  (when include-args-desc?
    (-> (map :name popover-tooltip-parts-desc) set)))

(def popover-tooltip-args-desc
  (when include-args-desc?
    [{:name :label         :required true                         :type "string | hiccup | r/atom" :validate-fn string-or-hiccup?    :description "the text (or component) for the tooltip"}
     {:name :showing?      :required true                         :type "boolean r/atom"                                             :description "an atom. When the value is true, the tooltip shows"}
     {:name :on-cancel     :required false                        :type "-> nil"                   :validate-fn fn?                  :description "a function which takes no params and returns nothing. Called when the popover is cancelled (e.g. user clicks away)"}
     {:name :close-button? :required false :default false         :type "boolean"                                                    :description "when true, displays the close button"}
     {:name :status        :required false                        :type "keyword"                  :validate-fn popover-status-type? :description [:span "controls background color of the tooltip. " [:code "nil/omitted"] " for black or one of " popover-status-types-list " (although " [:code ":validating"] " is only used by the input-text component)"]}
     {:name :anchor        :required true                         :type "hiccup"                   :validate-fn string-or-hiccup?    :description "the component the tooltip is attached to"}
     {:name :position      :required false :default :below-center :type "keyword"                  :validate-fn position?            :description [:span "relative to this anchor. One of " position-options-list]}
     {:name :no-clip?      :required false :default true          :type "boolean"                                                    :description "when an anchor is in a scrolling region (e.g. scroller component), the popover can sometimes be clipped. When this parameter is true (which is the default), re-com will use a different CSS method to show the popover. This method is slightly inferior because the popover can't track the anchor if it is repositioned"}
     {:name :width         :required false                        :type "string"                   :validate-fn string?              :description "specifies width of the tooltip"}
     {:name :class         :required false                        :type "string"                   :validate-fn string?              :description "CSS class names, space separated (applies to popover-anchor-wrapper component)"}
     {:name :style         :required false                        :type "CSS style map"            :validate-fn css-style?           :description "override component style(s) with a style map, only use in case of emergency (applies to popover-anchor-wrapper component)"}
     {:name :attr          :required false                        :type "HTML attr map"            :validate-fn html-attr?           :description [:span "HTML attributes, like " [:code ":on-mouse-move"] [:br] "No " [:code ":class"] " or " [:code ":style"] "allowed (applies to popover-anchor-wrapper component)"]}
     {:name :parts         :required false                        :type "map"                      :validate-fn (parts? popover-tooltip-parts) :description "See Parts section below."}
     {:name :src           :required false                        :type "map"                      :validate-fn map?                 :description [:span "Used in dev builds to assist with debugging. Source code coordinates map containing keys" [:code ":file"] "and" [:code ":line"]  ". See 'Debugging'."]}
     {:name :debug-as      :required false                        :type "map"                      :validate-fn map?                 :description [:span "Used in dev builds to assist with debugging, when one component is used implement another component, and we want the implementation component to masquerade as the original component in debug output, such as component stacks. A map optionally containing keys" [:code ":component"] "and" [:code ":args"] "."]}]))

(defn popover-tooltip
  "Renders text as a tooltip in Bootstrap popover style"
  [& {:keys [label showing? on-cancel close-button? status anchor position no-clip? width class style attr parts src debug-as]
      :or   {no-clip? true}
      :as   args}]
  (or
   (validate-args-macro popover-tooltip-args-desc args)
   (let [label         (deref-or-value label)
         cmerger (merge-css popover-tooltip-css-spec args)]
     (add-map-to-hiccup-call
      (cmerger :main)
      [popover-anchor-wrapper
       :src      src
       :debug-as (or debug-as (reflect-current-component))
       :showing? showing?
       :position (or position :below-center)
       :anchor   anchor
       :popover (add-map-to-hiccup-call
                 (cmerger :content-wrapper)
                 [popover-content-wrapper
                  :src            (at)
                  :no-clip?       no-clip?
                  :on-cancel      on-cancel
                  :width          width
                  :popover-color (case status
                                   :warning "#f57c00"
                                   :error   "#d50000"
                                   :info    "#333333"
                                   :success "#13C200"
                                   "black")
                  :padding        "3px 8px"
                  :arrow-length   6
                  :arrow-width    12
                  :arrow-gap      4
                  :tooltip-style? true
                  :body           (add-map-to-hiccup-call
                                   (cmerger :v-box {:status status})
                                   [v-box
                                    :src   (at)
                                    :children [(when close-button?
                                                 (add-map-to-hiccup-call
                                                  (cmerger :close-button-container)
                                                  [box
                                                   :src        (at)
                                                   :align-self :end
                                                   :child      (add-map-to-hiccup-call
                                                                (cmerger :close-button)
                                                                [close-button
                                                                 :src         (at)
                                                                 :on-click    #(if on-cancel
                                                                                 (on-cancel)
                                                                                 (reset! showing? false))
                                                                 :div-size    15
                                                                 :font-size   20
                                                                 :left-offset 5])]))
                                               label]])])]))))
