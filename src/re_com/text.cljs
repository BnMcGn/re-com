(ns re-com.text
  (:require-macros
   [re-com.core     :refer [handler-fn at reflect-current-component]]
   [re-com.validate :refer [validate-args-macro]])
  (:require
   [re-com.config   :refer [include-args-desc?]]
   [re-com.debug    :refer [->attr]]
   [re-com.box      :refer [v-box box line flex-child-style]]
   [re-com.util     :refer [deep-merge add-map-to-hiccup-call merge-css flatten-attr]]
   [re-com.validate :refer [title-levels-list title-level-type? css-style? html-attr? parts? string-or-hiccup?]]))

;; ------------------------------------------------------------------------------------
;;  Component: label
;; ------------------------------------------------------------------------------------

(def label-parts-desc
  (when include-args-desc?
    [{:name :wrapper   :level 0 :class "rc-label-wrapper"   :impl "[label]" :notes "Outer wrapper of the label."}
     {:type :legacy    :level 1 :class "rc-label"           :impl "[:span]"}]))

(def label-parts
  (when include-args-desc?
    (-> (map :name label-parts-desc) set)))

(def label-css-spec
  {:main {:class ["rc-label"]
          :style (flex-child-style "none")}
   :wrapper {:class ["rc-label-wrapper" "display-inline-flex"]}})

(def label-args-desc
  (when include-args-desc?
    [{:name :label    :required true  :type "anything"                                        :description "text or hiccup or whatever to display"}
     {:name :on-click :required false :type "-> nil"        :validate-fn fn?                  :description "a function which takes no params and returns nothing. Called when the label is clicked"}
     {:name :width    :required false :type "string"        :validate-fn string?              :description "a CSS width"}
     {:name :class    :required false :type "string"        :validate-fn string?              :description "CSS class names, space separated (applies to the label, not the wrapping div)"}
     {:name :style    :required false :type "CSS style map" :validate-fn css-style?           :description "additional CSS styles (applies to the label, not the wrapping div)"}
     {:name :attr     :required false :type "HTML attr map" :validate-fn html-attr?           :description [:span "HTML attributes, like " [:code ":on-mouse-move"] [:br] "No " [:code ":class"] " or " [:code ":style"] "allowed (applies to the label, not the wrapping div)"]}
     {:name :parts    :required false :type "map"           :validate-fn (parts? label-parts) :description "See Parts section below."}
     {:name :src      :required false :type "map"           :validate-fn map?                 :description [:span "Used in dev builds to assist with debugging. Source code coordinates map containing keys" [:code ":file"] "and" [:code ":line"]  ". See 'Debugging'."]}
     {:name :debug-as :required false :type "map"           :validate-fn map?                 :description [:span "Used in dev builds to assist with debugging, when one component is used implement another component, and we want the implementation component to masquerade as the original component in debug output, such as component stacks. A map optionally containing keys" [:code ":component"] "and" [:code ":args"] "."]}]))

(defn label
  "Returns markup for a basic label"
  [& {:keys [label on-click width class style attr parts src debug-as]
      :as   args}]
  (or
   (validate-args-macro label-args-desc args)
   (let [cmerger (merge-css label-css-spec args)]
     (add-map-to-hiccup-call
      (cmerger :wrapper)
      [box
       :debug-as (or debug-as (reflect-current-component))
       :src      src
       :width    width
       :align    :start
       :child    [:span
                  (merge
                   (cmerger :main)
                   (when on-click
                     {:on-click (handler-fn (on-click))}))
                  label]]))))

;; ------------------------------------------------------------------------------------
;;  Component: title
;; ------------------------------------------------------------------------------------

(def title-parts-desc
  (when include-args-desc?
    [{:name :wrapper   :level 0 :class "rc-title-wrapper"   :impl "[title]" :notes "Outer wrapper of the title."}
     {:type :legacy    :level 1 :class "rc-title"           :impl "[:span]"}
     {:name :underline :level 2 :class "rc-title-underline" :impl "[line]"}]))

(def title-parts
  (when include-args-desc?
    (-> (map :name title-parts-desc) set)))

(def title-css-spec
  {:wrapper {:class (fn [{:keys [level]}]
                      ["rc-title-wrapper" (when level (name level))])}
   :main {:class (fn [{:keys [level]}]
                   ["rc-title" "display-flex" (when level (name level))])
          :style (fn [{:keys [margin-top underline? margin-bottom]}]
                   (merge
                    (flex-child-style "none")
                    {:margin-top (or margin-top "0.6em")
                     :margin-bottom (when-not underline? (or margin-bottom "0.3em"))
                     ;; so that the margins are correct
                     :line-height 1}))}
   :underline {:class ["rc-title-underline"]
               :style (fn [{:keys [margin-bottom]}]
                        {:margin-bottom (or margin-bottom "0.3em")})}})

(def title-args-desc
  (when include-args-desc?
    [{:name :label         :required true                    :type "anything"                                          :description "title or hiccup or anything to display"}
     {:name :level         :required false                   :type "keyword"         :validate-fn title-level-type?    :description [:span "one of " title-levels-list ". If not provided then style the title using " [:code ":class"] " or " [:code ":style"]]}
     {:name :underline?    :required false  :default false   :type "boolean"                                           :description "if true, the title is underlined"}
     {:name :margin-top    :required false  :default "0.4em" :type "string"          :validate-fn string?              :description "CSS size for space above the title"}
     {:name :margin-bottom :required false  :default "0.1em" :type "string"          :validate-fn string?              :description "CSS size for space below the title"}
     {:name :class         :required false                   :type "string"          :validate-fn string?              :description "CSS class names, space separated (applies to the title, not the wrapping div)"}
     {:name :style         :required false                   :type "CSS style map"   :validate-fn css-style?           :description "CSS styles to add or override (applies to the title, not the wrapping div)"}
     {:name :attr          :required false                   :type "HTML attr map"   :validate-fn html-attr?           :description [:span "HTML attributes, like " [:code ":on-mouse-move"] [:br] "No " [:code ":class"] " or " [:code ":style"] "allowed (applies to the title, not the wrapping div)"]}
     {:name :parts         :required false                   :type "map"             :validate-fn (parts? title-parts) :description "See Parts section below."}
     {:name :src           :required false                   :type "map"             :validate-fn map?                 :description [:span "Used in dev builds to assist with debugging. Source code coordinates map containing keys" [:code ":file"] "and" [:code ":line"]  ". See 'Debugging'."]}
     {:name :debug-as      :required false                   :type "map"             :validate-fn map?                 :description [:span "Used in dev builds to assist with debugging, when one component is used implement another component, and we want the implementation component to masquerade as the original component in debug output, such as component stacks. A map optionally containing keys" [:code ":component"] "and" [:code ":args"] "."]}]))

(defn title
  "A title with four preset levels"
  [& {:keys [label level underline? margin-top margin-bottom class style attr parts src debug-as]
      :as   args}]
  (or
   (validate-args-macro title-args-desc args)
   (let [cmerger (merge-css title-css-spec args)]
     (add-map-to-hiccup-call
      (cmerger :wrapper {:level level})
      [v-box
       :src      src
       :debug-as (or debug-as (reflect-current-component))
       :children [[:span (merge (flatten-attr
                                 (cmerger :main {:level level :underline? underline?
                                                 :margin-top margin-top :margin-bottom margin-bottom})))
                   label]
                  (when underline? (add-map-to-hiccup-call
                                    (cmerger :underline {:margin-bottom margin-bottom})
                                    [line
                                     :src  (at)
                                     :size "1px"]))]]))))

;; ------------------------------------------------------------------------------------
;;  Component: p
;; ------------------------------------------------------------------------------------

(def p-css-spec
  {:main {:class ["rc-p"]
          :style {:flex          "none"
                  :width         "450px"
                  :min-width     "450px"
                  :margin-bottom "0.7em"}}})
(defn p
  "acts like [:p ] but uses a [:span] in place of the [:p] and adds bottom margin of 0.7ems which
  produces the same visual result.

  Creates a paragraph of body text, expected to have a font-size of 14px or 15px,
  which should have limited width.

  Why limited text width?  See http://baymard.com/blog/line-length-readability

  The actual font-size is inherited.

  At 14px, 450px will yield between 69 and 73 chars.
  At 15px, 450px will yield about 66 to 70 chars.
  So we're at the upper end of the preferred 50 to 75 char range.

  If the first child is a map, it is interpreted as a map of styles / attributes.

  This uses [:span] because React has become more unforgiving about nesting [:div]s under [:p]s and dumps
  a big red warning message in DevTools.

  By adding, for example, a [hyperlink] component within your `[:p]` (which contains a [:div]), you can get this warning message"
  [& children]
  (let [child1       (first children)    ;; it might be a map of attributes, including styles
        [m children] (if (map? child1)
                       [child1  (rest children)]
                       [{}      children])
        user (merge {:attr (reduce (partial dissoc m) [:class :style])}
                    (when-let [c (:class m)] {:class c})
                    (when-let [s (:style m)] {:style s}))
        cmerger (merge-css p-css-spec {})]
    [:span
     (flatten-attr (cmerger :main user))
     (into [:span] children)]))

;; Alias for backwards compatibility; p and p-span used to be different implementations.
(def p-span p)
