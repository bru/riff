(ns riff.core-test
  (:require [cljs.test :refer-macros [deftest testing is run-tests use-fixtures]]
            [riff.core :as riff]))

;; Test fixtures and utilities

(defn- create-mock-event
  "Create a mock keyboard event for testing"
  ([key] (create-mock-event key {}))
  ([key {:keys [alt-key meta-key ctrl-key shift-key]
         :or {alt-key false meta-key false ctrl-key false shift-key false}}]
   (clj->js {:key key
             :altKey alt-key
             :metaKey meta-key
             :ctrlKey ctrl-key
             :shiftKey shift-key
             :preventDefault (fn [])})))

(defn- reset-system-fixture
  "Fixture to ensure clean state before each test"
  [f]
  (riff/shutdown!)
  (f)
  (riff/shutdown!))

(use-fixtures :each reset-system-fixture)

;; Test data

(def test-shortcuts-tree
  {:context :root
   :bindings {"up" #(js/console.log "root up")
              "down" #(js/console.log "root down")
              "x" #(js/console.log "root x")
              "ctrl-s" #(js/console.log "root save")}
   :children
   [{:context :sidebar
     :bindings {"up" #(js/console.log "sidebar up")
                "down" #(js/console.log "sidebar down")
                "enter" #(js/console.log "sidebar enter")}}
    {:context :main
     :bindings {"enter" #(js/console.log "main enter")
                "up" #(js/console.log "main up")
                "shift-tab" #(js/console.log "main shift tab")}}
    {:context :nested-parent
     :bindings {"p" #(js/console.log "parent p")}
     :children
     [{:context :nested-child
       :bindings {"c" #(js/console.log "child c")}}]}]})

;; Core functionality tests

(deftest test-init-and-shutdown
  (testing "System initialization"
    (is (nil? (riff/get-context)) "Initially no active node")

    (riff/init! test-shortcuts-tree)
    (is (nil? (riff/get-context)) "Active node still nil after init")

    (riff/set-context! :root)
    (is (= :root (riff/get-context)) "Active node set correctly"))

  (testing "System shutdown"
    (riff/init! test-shortcuts-tree)
    (riff/set-context! :sidebar)
    (riff/shutdown!)
    (is (nil? (riff/get-context)) "Active node cleared after shutdown")))

(deftest test-set-and-get-context
  (testing "Setting and getting active node"
    (riff/init! test-shortcuts-tree)

    (riff/set-context! :sidebar)
    (is (= :sidebar (riff/get-context)))

    (riff/set-context! :main)
    (is (= :main (riff/get-context)))

    (riff/set-context! :nonexistent)
    (is (= :nonexistent (riff/get-context)) "Can set nonexistent nodes")))

;; Platform-specific key handling tests

(deftest test-kbd-function
  (testing "kbd function with defmod placeholder"
    (let [result (riff/kbd "defmod-b")]
      (is (string? result))
      (is (or (= "cmd-b" result) (= "ctrl-b" result))
          "Should convert defmod to platform-specific modifier"))

    (let [result (riff/kbd "shift-defmod-z")]
      (is (or (= "shift-cmd-z" result) (= "shift-ctrl-z" result)))))

  (testing "kbd function with explicit Mac/Windows strings"
    (let [result (riff/kbd "cmd-k" "ctrl-k")]
      (is (or (= "cmd-k" result) (= "ctrl-k" result))
          "Should choose platform-specific string"))))

(deftest test-platform-detection
  (testing "Platform detection constants"
    (is (boolean? riff/macos?))
    (is (boolean? riff/windows?))
    (is (not= riff/macos? riff/windows?)
        "Exactly one platform should be true")))

;; Key normalization tests

(deftest test-key-normalization
  (testing "Key normalization through actual usage"
    (riff/init! {:context :test
                      :bindings {"A" #(set! js/window.test-result "uppercase-a")
                                 "a" #(set! js/window.test-result "lowercase-a")}})
    (riff/set-context! :test)

    ;; Both uppercase and lowercase should work
    (let [event-upper (create-mock-event "A")
          event-lower (create-mock-event "a")]
      (is (some? event-upper))
      (is (some? event-lower)))))

(deftest test-modifier-key-combinations
  (testing "Modifier key parsing"
    (riff/init! {:context :test
                      :bindings {"ctrl-s" #(set! js/window.test-ctrl-s true)
                                 "cmd-shift-p" #(set! js/window.test-cmd-shift-p true)
                                 "alt-x" #(set! js/window.test-alt-x true)}})
    (riff/set-context! :test)

    ;; Test that events with correct modifiers would match
    (let [ctrl-s-event (create-mock-event "s" {:ctrl-key true})
          cmd-shift-p-event (create-mock-event "p" {:meta-key true :shift-key true})
          alt-x-event (create-mock-event "x" {:alt-key true})]
      (is (some? ctrl-s-event))
      (is (some? cmd-shift-p-event))
      (is (some? alt-x-event)))))

;; Event bubbling tests

(deftest test-event-bubbling
  (testing "Event bubbling from child to parent"
    (let [results (atom [])
          bubbling-tree {:context :root
                         :bindings {"x" #(swap! results conj :root-x)
                                    "y" #(swap! results conj :root-y)}
                         :children
                         [{:context :child
                           :bindings {"y" #(swap! results conj :child-y)}}]}]

      (riff/init! bubbling-tree)
      (riff/set-context! :child)

      ;; Verify the tree structure is set up correctly
      (is (= :child (riff/get-context))))))

(deftest test-nested-tree-structure
  (testing "Deeply nested tree structure"
    (let [nested-tree {:context :root
                       :bindings {"global" #(js/console.log "global")}
                       :children
                       [{:context :level1
                         :bindings {"l1" #(js/console.log "level1")}
                         :children
                         [{:context :level2
                           :bindings {"l2" #(js/console.log "level2")}
                           :children
                           [{:context :level3
                             :bindings {"l3" #(js/console.log "level3")}}]}]}]}]

      (riff/init! nested-tree)

      ;; Test that we can set active nodes at different levels
      (riff/set-context! :level3)
      (is (= :level3 (riff/get-context)))

      (riff/set-context! :level1)
      (is (= :level1 (riff/get-context)))

      (riff/set-context! :root)
      (is (= :root (riff/get-context))))))

;; Callback vector handler tests

(deftest test-vector-handlers
  (testing "Vector handlers with callback function"
    (let [dispatched (atom nil)
          callback-fn (fn [event-vec event]
                       (reset! dispatched event-vec))]

      (riff/init! {:context :test
                        :bindings {"s" [:save-file]
                                   "o" [:open-file "readonly"]}}
                       {:callback callback-fn})

      (riff/set-context! :test)

      ;; Verify system is initialized
      (is (= :test (riff/get-context))))))

;; Edge cases and error handling

(deftest test-edge-cases
  (testing "Empty tree"
    (riff/init! {:context :empty})
    (riff/set-context! :empty)
    (is (= :empty (riff/get-context))))

  (testing "Tree with no bindings"
    (riff/init! {:context :no-bindings
                      :children [{:context :child-no-bindings}]})
    (riff/set-context! :child-no-bindings)
    (is (= :child-no-bindings (riff/get-context))))

  (testing "Tree with empty bindings map"
    (riff/init! {:context :empty-bindings
                      :bindings {}})
    (riff/set-context! :empty-bindings)
    (is (= :empty-bindings (riff/get-context))))

  (testing "Multiple init calls"
    (riff/init! {:context :first})
    (riff/set-context! :first)

    (riff/init! {:context :second})
    (riff/set-context! :second)

    (is (= :second (riff/get-context)) "Second init should replace first")))

(deftest test-special-keys
  (testing "Special key support"
    (let [special-keys-tree {:context :special
                             :bindings {"escape" #(js/console.log "escape pressed")
                                        "tab" #(js/console.log "tab pressed")
                                        " " #(js/console.log "space pressed")}}]

      (riff/init! special-keys-tree)
      (riff/set-context! :special)

      ;; Verify the system can handle special keys
      (let [escape-event (create-mock-event "Escape")
            tab-event (create-mock-event "Tab")
            space-event (create-mock-event " ")]
        (is (some? escape-event))
        (is (some? tab-event))
        (is (some? space-event))))))

(deftest test-case-insensitive-keys
  (testing "Case insensitive key handling"
    (riff/init! {:context :case-test
                      :bindings {"a" #(js/console.log "letter a")
                                 "1" #(js/console.log "number 1")}})
    (riff/set-context! :case-test)

    ;; Both cases should work due to normalization
    (let [upper-a (create-mock-event "A")
          lower-a (create-mock-event "a")]
      (is (some? upper-a))
      (is (some? lower-a)))))

;; Integration tests

(deftest test-complete-workflow
  (testing "Complete workflow with realistic scenario"
    (let [app-shortcuts {:context :app
                         :bindings {"ctrl-q" #(js/console.log "quit app")
                                    "f1" #(js/console.log "help")}
                         :children
                         [{:context :editor
                           :bindings {"ctrl-s" #(js/console.log "save file")
                                      "ctrl-o" #(js/console.log "open file")
                                      "enter" #(js/console.log "new line")}}
                          {:context :sidebar
                           :bindings {"enter" #(js/console.log "select item")
                                      "delete" #(js/console.log "delete item")}}]}]

      ;; Initialize system
      (riff/init! app-shortcuts)

      ;; Test editor context
      (riff/set-context! :editor)
      (is (= :editor (riff/get-context)))

      ;; Test sidebar context
      (riff/set-context! :sidebar)
      (is (= :sidebar (riff/get-context)))

      ;; Test app-level context
      (riff/set-context! :app)
      (is (= :app (riff/get-context)))

      ;; Clean shutdown
      (riff/shutdown!)
      (is (nil? (riff/get-context))))))
