(ns riff.core
  "Hierarchical keyboard shortcut management for ClojureScript applications.

  This library provides a tree-based shortcut system with context-aware key bindings
  that bubble up from child to parent nodes. Supports platform-specific modifiers
  (Mac cmd vs Windows ctrl) and flexible handler functions or callback vectors."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

;; Platform Detection

(def windows?
  "True if running on Windows platform"
  (neg? (.indexOf js/navigator.userAgent "Mac OS X")))

(def macos?
  "True if running on macOS platform"
  (not windows?))

;; Utility Functions


(defn kbd
  "Helper for defining cross-platform keyboard shortcuts.

  Single arity: Takes a keystring with 'defmod' as a placeholder for the
  platform-specific modifier (cmd on Mac, ctrl on Windows).

  Two arity: Takes separate Mac and Windows keystrings for full control.

  Examples:
  ```clojure
  (kbd \"defmod-b\")              ; => \"cmd-b\" on Mac, \"ctrl-b\" on Windows
  (kbd \"shift-defmod-z\")        ; => \"shift-cmd-z\" on Mac, \"shift-ctrl-z\" on Windows
  (kbd \"cmd-k\" \"ctrl-k\")      ; => \"cmd-k\" on Mac, \"ctrl-k\" on Windows
  ```"
  ([keystring]
   (let [bits (.split keystring #"-(?!$)")]
     (->> bits
          (map #(if-not (= "defmod" %) % (if macos? "cmd" "ctrl")))
          (str/join "-"))))
  ([mac-string win-string]
   (if macos? mac-string win-string)))

;; Core Shortcut System

;; Internal state atom holding the shortcuts tree, active node, and event listener
(defonce ^:private state
  (atom {:shortcuts-tree nil
         :active-node nil
         :event-listener nil}))

(defn- normalize-modifier
  "Normalize modifier string to lowercase, converting 'defmod' to platform-specific modifier"
  [key-str]
  (let [lc-key (str/lower-case key-str)]
    (if (= "defmod" lc-key)
      (if windows? "ctrl" "cmd")
      lc-key)))

(defn- normalize-key
  "Normalize key string to lowercase"
  [key-str]
  (str/lower-case key-str))

(defn- parse-key-combination
  "Parse a key combination string into modifiers and key.

  Example: \"shift-cmd-a\" => {:modifiers #{\"shift\" \"cmd\"} :key \"a\"}"
  [key-str]
  (let [parts (str/split key-str #"\-")
        modifiers (set (map normalize-modifier (butlast parts)))
        key (normalize-key (last parts))]
    {:modifiers modifiers
     :key key}))

(defn- find-node-by-id
  "Find a node in the tree by its ID"
  [tree node-id]
  (cond
    (= (:context tree) node-id) tree
    (:children tree) (some #(find-node-by-id % node-id) (:children tree))
    :else nil))

(defn- find-handler-for-key
  "Find handler for a key combination, bubbling up the tree if not found"
  [tree key-combo]
  (loop [current-tree tree]
    (when current-tree
      (if-let [handler (some #(get (:bindings current-tree) {:modifiers (:modifiers key-combo) :key %})
                             (:keys key-combo))]
        ;; Handler found - normalize it to a function
        (do
          (cond
            (fn? handler) handler
            :else
            (fn [event]
              (when-let [callback (:callback @state)]
                (callback handler event)))))
        
        ;; Not found, bubble up to parent
        (do
          (recur (:parent current-tree)))))))

(defn- add-parent-references
  "Add parent references to all nodes in the tree for upward traversal"
  [tree parent]
  (let [tree-with-parent (assoc tree :parent parent)]
    (if (:children tree-with-parent)
      (assoc tree-with-parent
             :children (mapv #(add-parent-references % tree-with-parent)
                             (:children tree-with-parent)))
      tree-with-parent)))

(defn- preprocess-tree
  "Preprocess the tree to add parent references and parse key combinations"
  [tree]
  (let [tree-with-parents (add-parent-references tree nil)
        parse-bindings (fn [bindings]
                         (reduce-kv (fn [acc key-str handler]
                                      (assoc acc (parse-key-combination key-str) handler))
                                    {} bindings))]
    (walk/postwalk
     (fn [node]
       (if (and (map? node) (:bindings node))
         (assoc node :bindings (parse-bindings (:bindings node)))
         node))
     tree-with-parents)))

(defn- combo-keys
  "Extract possible key representations from a keyboard event"
  [event]
  (let [key (.-key event)
        code (.-code event)
        code-key
        (condp re-matches code
          #"^Digit.*" :>> #(subs % 5)
          #"^Key.*" :>>   #(subs % 3)
          #"^Arrow.*" :>> #(subs % 5)
          nil)]
    (->> [key code-key]
         (filter identity)
         (map str/lower-case))))

(defn- handle-keydown
  "Handle keydown events by finding and executing the appropriate handler"
  [event]
  (let [{:keys [shortcuts-tree active-node]} @state
        event-key (.-key event)
        mod-only? (and (string? event-key)
                      (some #{(str/lower-case event-key)} ["meta" "alt" "shift" "ctrl"]))]
    (when (and event shortcuts-tree active-node (not mod-only?))
      (let [current-node (find-node-by-id shortcuts-tree active-node)
            key-combo {:modifiers (cond-> #{}
                                    (.-altKey event) (conj "alt")
                                    (.-metaKey event) (conj "cmd")
                                    (.-ctrlKey event) (conj "ctrl")
                                    (.-shiftKey event) (conj "shift"))
                       :keys (combo-keys event)}]
        (when-let [handler (find-handler-for-key current-node key-combo)]
          (handler event))))))

;; Public API

(defn init!
  "Initialize the keyboard shortcuts system with a shortcuts tree.

  The shortcuts tree is a nested map structure:
  - :context - The node identifier (keyword)
  - :bindings - Map of key-string to handler (function or callback vector)
  - :children - Optional vector of child nodes

  Handlers can be:
  - Functions: Called directly with the keyboard event
  - Vectors: Passed to the callback function (set via options)

  Options:
  - :callback - Function called with [handler-vector event] when handler is a vector

  Example:
  ```clojure
  (init! {:context :root
          :bindings {\"cmd-s\" #(js/console.log \"Save!\")
                     \"escape\" [:ui/close-modal]}
          :children [{:context :editor
                      :bindings {\"cmd-b\" #(js/console.log \"Bold!\")}}]}
         {:callback (fn [event-vec event] (dispatch event-vec))})
  ```"
  ([shortcuts-tree] (init! shortcuts-tree nil))
  ([shortcuts-tree options]
   (let [processed-tree (preprocess-tree shortcuts-tree)]
     (swap! state assoc
            :shortcuts-tree processed-tree
            :callback (:callback options))
     (when-let [old-listener (:event-listener @state)]
       (.removeEventListener js/document "keydown" old-listener))
     (let [new-listener #(handle-keydown %)]
       (.addEventListener js/document "keydown" new-listener)
       (swap! state assoc :event-listener new-listener)))))

(defn set-context!
  "Set the currently active node in the shortcuts tree.

  The active node determines which shortcuts are currently active. When a key
  is pressed, the system looks for a handler in the active node, and if not
  found, bubbles up to parent nodes.

  Example:
  ```clojure
  (set-context! :editor)  ; Enable editor shortcuts
  (set-context! :sidebar) ; Switch to sidebar shortcuts
  ```"
  [node-id]
  (swap! state assoc :active-node node-id))

(defn get-context
  "Get the currently active node identifier.

  Returns the keyword identifying the current active node, or nil if none is set.

  Example:
  ```clojure
  (get-context) ; => :editor
  ```"
  []
  (:active-node @state))

(defn shutdown!
  "Clean up the keyboard shortcuts system.

  Removes the event listener and resets all state. Should be called when
  tearing down the application or switching to a different shortcuts system.

  Example:
  ```clojure
  (shutdown!)
  ```"
  []
  (when-let [listener (:event-listener @state)]
    (.removeEventListener js/document "keydown" listener))
  (reset! state {:shortcuts-tree nil
                 :active-node nil
                 :event-listener nil
                 :callback nil}))
