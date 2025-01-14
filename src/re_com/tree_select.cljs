(ns re-com.tree-select
  (:require-macros
   [re-com.core :refer [handler-fn at]])
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [reagent.core          :as r]
   [re-com.config         :refer [include-args-desc?]]
   [re-com.util           :refer [deref-or-value remove-id-item add-map-to-hiccup-call flatten-attr merge-css]]
   [re-com.box            :refer [h-box v-box box gap]]
   [re-com.checkbox       :refer [checkbox]]
   [re-com.popover        :refer [backdrop]]
   [re-com.validate       :as validate :refer [css-style? html-attr? parts?] :refer-macros [validate-args-macro]]))

(def tree-select-dropdown-parts-desc
  (when include-args-desc?
    [{:type :legacy           :level 0 :class "rc-dropdown"                              :impl "[tree-select-dropdown]"}
     {:name :wrapper          :level 1 :class "rc-tree-select-dropdown-wrapper"          :impl "[:div]"}
     {:name :anchor           :level 2 :class "rc-tree-select-dropdown-anchor"           :impl "[h-box]"}
     {:name :counter          :level 3 :class "rc-tree-select-dropdown-counter"          :impl "[box]"}
     {:name :anchor-expander  :level 3 :class "rc-tree-select-dropdown-anchor-expander"  :impl "[box]"}
     {:name :backdrop         :level 2 :class "rc-tree-select-dropdown-backdrop"         :impl "[:div]"}
     {:name :dropdown-wrapper :level 2 :class "rc-tree-select-dropdown-dropdown-wrapper" :impl "[v-box]"}
     {:name :body             :level 3 :class "rc-tree-select-dropdown-body"             :impl "[tree-select]"}]))

(def tree-select-dropdown-css-spec
  {:main {:class ["rc-tree-select-dropdown" "fade in"]
         :style {:position "relative"}}
   :wrapper {:class ["rc-tree-select-dropdown-wrapper"]
             :style (fn [{:keys [max-width min-width]}]
                      {:display  "inline-block"
                       :max-width max-width
                       :min-width min-width})}
   :anchor {:class ["rc-tree-select-dropdown-anchor"]
            :style (fn [{:keys [disabled? min-width max-width]}]
                     {:min-width        min-width
                      :max-width        max-width
                      :background-color (if disabled? "#EEE" "white")
                      :border           "1px solid lightgrey"
                      :border-radius    "2px"
                      :overflow         "hidden"
                      :cursor           (if disabled? "default" "pointer")})}
   :anchor-label {:style (fn [{:keys [max-width]}]
                           {:max-width     max-width
                            :white-space   "nowrap"
                            :overflow      "hidden"
                            :text-overflow "ellipsis"})}
   :counter {:class ["rc-tree-select-dropdown-counter"]
             :style {:margin-left "10px"
                     :margin-right "10px"
                     :opacity "50%"}}
   :anchor-expander {:class ["rc-tree-select-dropdown-anchor-expander"]}
   :backdrop {:class ["rc-tree-select-dropdown-backdrop"]}
   :dropdown-wrapper {:class ["rc-tree-select-dropdown-dropdown-wrapper"]
                      :style {:position         "absolute"
                              :background-color "white"
                              :border-radius    "4px"
                              :border           "1px solid #ccc"
                              :padding          "5px 10px 5px 5px"
                              :box-shadow       "0 5px 10px rgba(0, 0, 0, .2)"}}
   :body {:class ["rc-tree-select-dropdown-body"]}})

(def tree-select-dropdown-parts
  (when include-args-desc?
    (-> (map :name tree-select-dropdown-parts-desc) set)))

(def tree-select-parts-desc
  (when include-args-desc?
    [{:type :legacy   :level 0 :class "rc-tree-select"          :impl "[tree-select]"}
     {:name :wrapper  :level 1 :class "rc-tree-select-wrapper"  :impl "[v-box]"}
     {:name :choice   :level 2 :class "rc-tree-select-choice"   :impl "[h-box]"}
     {:name :group    :level 2 :class "rc-tree-select-group"    :impl "[h-box]"}
     {:name :offset   :level 3 :class "rc-tree-select-offset"   :impl "[box]"}
     {:name :expander :level 3 :class "rc-tree-select-expander" :impl "[box]"}
     {:name :checkbox :level 3 :class "rc-tree-select-checkbox" :impl "[checkbox]"}]))

(def tree-select-css-spec
  {:main {:class ["rc-tree-select"]}
   :wrapper {:class ["rc-tree-select-wrapper"]
             :style {:overflow-y "scroll"}}
   :choice {:class ["rc-tree-select-choice"]}
   :group {:class ["rc-tree-select-group"]}
   :offset {:class ["rc-tree-select-offset"]
            :style {:visibility "hidden"}}
   :expander {:class ["rc-tree-select-expander"]
              :style {:cursor "pointer"}}
   :checkbox {:class ["rc-tree-select-checkbox"]}})

(def tree-select-parts
  (when include-args-desc?
    (-> (map :name tree-select-parts-desc) set)))

(def tree-select-args-desc
  (when include-args-desc?
    [{:name        :choices
      :required    true
      :type        "vector of maps | r/atom"
      :validate-fn validate/vector-of-maps?
      :description [:span "Each map represents a choice. Values corresponding to id, & label are extracted by the functions "
                    [:code ":id-fn"] " & " [:code ":label-fn"] ". See below."]}
     {:name        :model
      :required    true
      :type        "a set of ids | r/atom"
      :description [:span "The set of the ids for currently selected choices. If nil or empty, see "
                    [:code ":placeholder"] "."]}
     {:name        :groups
      :required    false
      :default     "(reagent/atom nil)"
      :type        "a set of paths | r/atom"
      :description [:span "The set of currently expanded group paths."]}
     {:name        :initial-expanded-groups
      :required    false
      :type        "keyword | set of paths"
      :description [:span "How to expand groups when the component first mounts."]}
     {:name        :on-change
      :required    true
      :type        "[set of choice ids, set of group vectors]  -> nil"
      :validate-fn fn?
      :description [:span "This function is called whenever the selection changes. It is also responsible for updating the value of "
                    [:code ":model"] " as needed."]}
     {:name        :min-width
      :required    false
      :type        "string"
      :validate-fn string?
      :description "Minimum width of the outer wrapper."}
     {:name        :max-width
      :required    false
      :type        "string"
      :validate-fn string?
      :description "Maximum width of the outer wrapper."}
     {:name        :min-height
      :required    false
      :type        "string"
      :validate-fn string?
      :description "Minimum height of the outer wrapper."}
     {:name        :max-height
      :required    false
      :type        "string"
      :validate-fn string?
      :description "Maximum height of the outer wrapper."}
     {:name        :disabled?
      :required    false
      :default     false
      :type        "boolean"
      :description "if true, no user selection is allowed"}
     {:name        :choice-disabled-fn
      :required    false
      :default     nil
      :type        "choice map -> boolean"
      :validate-fn ifn?
      :description [:span "Predicate on the set of maps given by "
                    [:code "choices"] ". Disables the subset of choices for which "
                    [:code "choice-disabled?"] " returns " [:code "true"] "."]}
     {:name        :label-fn
      :required    false
      :default     ":label"
      :type        "map -> hiccup"
      :validate-fn ifn?
      :description [:span "A function which can turn a choice into a displayable label. Will be called for each element in "
                    [:code ":choices"] ". Given one argument, a choice map, it returns a string or hiccup."]}
     {:name        :group-label-fn
      :required    false
      :default     "(comp name last)"
      :type        "vector -> hiccup"
      :validate-fn ifn?
      :description [:span "A function which can turn a group vector into a displayable label. Will be called for each element in "
                    [:code ":groups"] ". Given one argument, a group vector, it returns a string or hiccup."]}
     {:name :class
      :required false
      :type "string"
      :validate-fn string?
      :description "CSS class names, space separated (applies to the outer container)"}
     {:name :style
      :required false
      :type "CSS style map"
      :validate-fn css-style?
      :description "CSS styles to add or override (applies to the outer container)"}
     {:name :attr
      :required false
      :type "HTML attr map"
      :validate-fn html-attr?
      :description [:span "HTML attributes, like " [:code ":on-mouse-move"] [:br]
                    "No " [:code ":class"] " or " [:code ":style"] "allowed (applies to the outer container)"]}
     {:name :parts
      :required false
      :type "map"
      :validate-fn (parts? tree-select-parts)
      :description "See Parts section below."}
     {:name :src
      :required false
      :type "map"
      :validate-fn map?
      :description [:span "Used in dev builds to assist with debugging. Source code coordinates map containing keys"
                    [:code ":file"] "and" [:code ":line"]  ". See 'Debugging'."]}]))

(def tree-select-dropdown-args-desc
  (when include-args-desc?
    (into
     (remove-id-item :parts tree-select-args-desc {:id-fn :name})
     [{:name :placeholder
       :required false
       :type "string"
       :validate-fn string?
       :description "(Dropdown version only). Background text shown when there's no selection."}
      {:name :field-label-fn
       :required false
       :type "map -> string or hiccup"
       :validate-fn ifn?
       :description (str "(Dropdown version only). Accepts a map, including keys :items, :group-label-fn and :label-fn. "
                         "Can return a string or hiccup, which will be rendered inside the dropdown anchor box.")}
      {:name :alt-text-fn
       :required false
       :type "map -> string"
       :validate-fn ifn?
       :description (str "(Dropdown version only). Accepts a map, including keys :items, :group-label-fn and :label-fn. "
                         "Returns a string that will display in the native browser tooltip that appears on mouse hover.")}
      {:name :parts
       :required false
       :type "map"
       :validate-fn (parts? tree-select-dropdown-parts)
       :description "See Parts section below."}])))

(defn offset [& {:keys [level] :as args}]
  (let [cmerger (merge-css tree-select-css-spec args)]
    (add-map-to-hiccup-call
     (cmerger :offset)
     [box
      :src (at)
      :child (apply str (repeat level "⯈"))])))

(defn choice-checkbox [{:keys [checked? toggle! label disabled? indeterminate?] :as args}]
  (let [cmerger (merge-css tree-select-css-spec args)]
    (add-map-to-hiccup-call
     (cmerger :checkbox {:attr {:ref #(when % (set! (.-indeterminate %) indeterminate?))}})
     [checkbox
      :src (at)
      :model checked?
      :on-change toggle!
      :label label
      :disabled? disabled?])))

(defn choice-item [& {:keys [level showing? parts] :as args}]
  (when showing?
    (let [cmerger (merge-css tree-select-css-spec args)]
      (add-map-to-hiccup-call
       (cmerger :choice)
       [h-box
        :src (at)
        :children
        [[offset :parts parts :level level]
         [choice-checkbox args]]]))))

(defn group-item [& {:keys [label checked? toggle! hide-show! level showing? open? disabled? parts] :as args}]
  (when showing?
    (let [cmerger (merge-css tree-select-css-spec args)]
      (add-map-to-hiccup-call
       (cmerger :group)
       [h-box
        :src (at)
        :children
        [[offset :parts parts :level (dec level)]
         (add-map-to-hiccup-call
          (cmerger :expander {:attr {:on-click hide-show!}})
          [box
           :src (at)
           :child
           (if open? "⯆" "⯈")])
         " "
         [choice-checkbox (into args {:indeterminate? (= :some checked?)})]]]))))

(def group? (comp #{:group} :type))

(defn as-v [x] (when (some? x) (if (vector? x) x [x])))

(defn ancestor-paths [path]
  (some->> path as-v (iterate butlast) (take-while identity) (map vec)))

(defn infer-groups [items]
  (into #{} (comp
             (keep :group)
             (map as-v)
             (mapcat ancestor-paths)
             (map #(do {:type :group :group %}))
             (distinct))
        items))

(def infer-groups* (memoize infer-groups))

(defn toggle [s k]
  (if (contains? s k)
    (disj s k)
    ((fnil conj #{}) s k)))

(defn descendant? [group-v item]
  (= group-v (vec (take (count group-v) (as-v (:group item))))))

(defn filter-descendants [group-v choices]
  (filter (partial descendant? group-v) choices))

(def filter-descendants* (memoize filter-descendants))

(defn sort-items [items] (->> items (sort-by (juxt (comp #(apply str (as-v %)) :group)
                                                   (complement group?)))))

(def group-label (comp str/capitalize name last :group))

(defn current-choices [model choices] (into #{} (filter (comp model :id) choices)))

(defn current-groups [current-choices] (infer-groups* current-choices))

(defn full-groups [model choices]
  (let [current-choices           (current-choices model choices)
        current-groups            (current-groups current-choices)
        full? (fn [{:keys [group]}]
                (let [group-v        (as-v group)
                      descendant-ids (map :id (filter-descendants* group-v choices))]
                  (every? model descendant-ids)))]
    (into #{} (filter full? current-groups))))

(defn tree-select
  [& {:keys [model choices initial-expanded-groups id-fn]
      :or   {id-fn            :id}}]
  (let [expanded-groups           (r/atom nil)]
    (when-some [initial-expanded-groups (deref-or-value initial-expanded-groups)]
      (reset! expanded-groups (case initial-expanded-groups
                                :all    (set (map :group (infer-groups choices)))
                                :none   #{}
                                :chosen (into #{}
                                              (comp (filter #(contains? (deref-or-value model) (id-fn %)))
                                                    (keep :group)
                                                    (mapcat ancestor-paths))
                                              choices)
                                initial-expanded-groups)))
    (fn tree-select-render
      [& {:keys [choices group-label-fn disabled? min-width max-width min-height max-height on-change choice-disabled-fn label-fn parts class style attr] :as args}]
      (or
       (validate-args-macro tree-select-args-desc args)
       (let [choices        (deref-or-value choices)
             disabled?      (deref-or-value disabled?)
             model          (deref-or-value model)
             label-fn       (or label-fn :label)
             group-label-fn (or group-label-fn group-label)
             items          (->> choices infer-groups* (into choices) sort-items)
             item           (fn [item-props]
                              (let [{:keys [id group] :as item-props} (update item-props :group as-v)]
                                (if (group? item-props)
                                  (let [descendants (filter-descendants* group choices)
                                        descendant-ids (map :id descendants)
                                        checked?       (cond
                                                         (every? model descendant-ids) :all
                                                         (some   model descendant-ids) :some)
                                        new-model (->> (cond->> descendants choice-disabled-fn (remove choice-disabled-fn))
                                                       (map :id)
                                                       ((if (= :all checked?) set/difference set/union) model)
                                                       set)
                                        new-groups (into #{} (map :group) (full-groups new-model choices))]
                                    [group-item
                                     :group      item-props
                                     :label      (group-label-fn item-props)
                                     :parts     parts
                                     :hide-show! #(swap! expanded-groups toggle group)
                                     :toggle!    (handler-fn (on-change new-model new-groups))
                                     :open?      (contains? @expanded-groups group)
                                     :checked?   checked?
                                     :model      model
                                     :disabled?  (or disabled? (when choice-disabled-fn (every? choice-disabled-fn descendants)))
                                     :showing?   (every? (set @expanded-groups) (rest (ancestor-paths group)))
                                     :level      (count group)])
                                  [choice-item
                                   :choice    item-props
                                   :model     model
                                   :label     (label-fn item-props)
                                   :parts     parts
                                   :showing?  (if-not group
                                                true
                                                (every? (set @expanded-groups) (ancestor-paths group)))
                                   :disabled? (or disabled? (when choice-disabled-fn (choice-disabled-fn item-props)))
                                   :toggle!   (handler-fn (let [new-model (toggle model id)
                                                                new-groups (into #{} (map :group) (full-groups new-model choices))]
                                                            (on-change new-model new-groups)))
                                   :checked?  (get model id)
                                   :level     (inc (count group))])))
             cmerger (merge-css tree-select-css-spec args)]
         (add-map-to-hiccup-call
          (cmerger :wrapper)
          [v-box
           :src (at)
           :min-width min-width
           :max-width max-width
           :min-height min-height
           :max-height max-height
           :children (mapv item items)]))))))

(defn field-label [{:keys [items group-label-fn label-fn]}]
  (let [item-label-fn             #((if (group? %) group-label-fn label-fn) %)]
    (str/join ", " (map item-label-fn items))))

(defn labelable-items [model choices]
  (let [current-choices           (into #{} (filter (comp model :id) choices))
        current-groups            (infer-groups* current-choices)
        full?                     (fn [{:keys [group]}]
                                    (let [group-v        (as-v group)
                                          descendant-ids (map :id (filter-descendants* group-v choices))]
                                      (every? model descendant-ids)))
        full-groups               (into #{} (filter full? current-groups))
        highest-groups            (loop [[group & remainder] (sort-by (comp count :group) full-groups)
                                         acc                 []]
                                    (if-not group
                                      acc
                                      (let [group-v (as-v (:group group))]
                                        (recur (remove (partial descendant? group-v) remainder)
                                               (conj acc group)))))
        highest-group-descendants (into #{} (mapcat #(filter-descendants* (:group %) current-choices) highest-groups))]
    (->> highest-groups
         (into current-choices)
         (remove highest-group-descendants)
         sort-items)))

(defn tree-select-dropdown [{:keys [expanded-groups]
                             :or   {expanded-groups (r/atom nil)}}]
  (let [showing?       (r/atom false)
        !anchor-span   (r/atom nil)
        !anchor-label  (r/atom "")]
    (fn tree-select-dropdown-render
      [& {:keys [choices group-label-fn disabled? min-width max-width min-height max-height on-change on-groups-change
                 label-fn alt-text-fn height parts style model expanded-groups placeholder id-fn alt-text-fn field-label-fn]
          :or   {placeholder      "Select an item..."
                 id-fn            :id
                 label-fn         :label}
          :as args}]
      (let [label-fn        (or label-fn :label)
            alt-text-fn     (or alt-text-fn #(->> % :items (map (or label-fn :label)) (str/join ", ")))
            group-label-fn  (or group-label-fn (comp name last :group))
            field-label-fn  (or field-label-fn field-label)
            labelable-items (labelable-items (deref-or-value model) choices)
            anchor-label    (field-label-fn {:items labelable-items
                                             :label-fn label-fn
                                             :group-label-fn group-label-fn})
            cmerger (merge-css tree-select-dropdown-css-spec args)
            body   (add-map-to-hiccup-call
                    (cmerger :dropdown-wrapper)
                    [v-box
                     :src (at)
                     :height "fit-content"
                     :children [(add-map-to-hiccup-call
                                 (cmerger :body)
                                 [tree-select
                                  :choices choices
                                  :group-label-fn group-label-fn
                                  :disabled? disabled?
                                  :min-width min-width
                                  :max-width max-width
                                  :min-height min-height
                                  :max-height max-height
                                  :on-change on-change
                                  :label-fn label-fn
                                  :model model])]])
            anchor (fn []
                     (let [model     (deref-or-value model)
                           disabled? (deref-or-value disabled?)]
                       (add-map-to-hiccup-call
                        (cmerger :anchor {:disabled? disabled?
                                          :min-width min-width
                                          :max-width max-width
                                          :attr
                                          {:on-click #(swap! showing? not)}})
                        [h-box
                         :src       (at)
                         :height    height
                         :padding   "0px 6px"
                         :children  [(if (empty? model)
                                       placeholder
                                       (let [selections (filter (comp (set model) id-fn) choices)
                                             _          (reset! !anchor-label anchor-label)]
                                         [:span (merge
                                                 (flatten-attr (cmerger :anchor-label {:max-width max-width}))
                                                 {:ref   #(reset! !anchor-span %)
                                                  :title (alt-text-fn {:items labelable-items
                                                                       :label-fn label-fn
                                                                       :group-label-fn group-label-fn})})
                                          anchor-label]))
                                     [gap
                                      :src (at)
                                      :size "1"]
                                     (when-let [model (seq model)]
                                       (add-map-to-hiccup-call
                                        (cmerger :counter)
                                        [box
                                         :src (at)
                                         :child (str (count model))]))
                                     (when-not disabled?
                                       (add-map-to-hiccup-call
                                        (cmerger :anchor-expander)
                                        [box
                                         :src (at)
                                         :child
                                         (if @showing? "▲" "▼")]))]])))]
        [:div (flatten-attr (cmerger :wrapper {:max-width max-width :min-width min-width}))
         [anchor]
         (when @showing?
           [:div (flatten-attr (cmerger :main))
            (add-map-to-hiccup-call
             (cmerger :backdrop)
             [backdrop
              :on-click #(reset! showing? false)])
            body])]))))
