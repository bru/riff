# riff

A hierarchical keyboard shortcut management library for ClojureScript applications.
The design of this library has been inspired and heavily influenced by [keybind](https://github.com/piranha/keybind), but eventually diverged due to the specific needs of the application that it's been extracted from.

## Features

- **Hierarchical Context System**: Organize shortcuts in a tree structure where child nodes can override parent bindings
- **Event Bubbling**: Shortcuts "bubble up" from child to parent nodes if not handled
- **Platform-Aware**: Automatic detection and handling of Mac (cmd) vs Windows (ctrl) modifier keys
- **Flexible Handlers**: Support for both function handlers and callback vectors (e.g. for use with re-frame's dispatch)
- **Context Switching**: Easy switching between different shortcut contexts (e.g. navbar, footer, editor, etc.)
- **Clean API**: Simple, functional API with no framework dependencies

## Installation

Add to your `deps.edn`:

```clojure
{:deps {net.clojars.bru/riff {:mvn/version "0.1.0"}}}
```

Or with shadow-cljs, add to your `shadow-cljs.edn` deps.

## Quick Start

```clojure
(ns my-app.core
  (:require [riff.core :as riff]))

;; Define your shortcuts tree
(def app-shortcuts
  {:context :root
   :bindings {"escape" #(js/console.log "Close modal")
              "ctrl-q" #(js/console.log "Quit")}
   :children
   [{:context :editor
     :bindings {"defmod-s" #(js/console.log "Save file") ; this will be triggered by `cmd-s` on macos and `ctrl+s` on windows
                "cmd-b" #(js/console.log "Toggle bold")}}
    {:context :sidebar
     :bindings {"enter" #(js/console.log "Select item")
                "delete" #(js/console.log "Delete item")}}]})

;; Initialize the system
(riff/init! app-shortcuts)

;; Set the active context
(riff/set-context! :editor)

;; Now cmd-s will save, and escape will close modal (bubbles from :editor to :root)

;; Switch contexts
(riff/set-context! :sidebar)
;; Now enter selects item

;; Clean up
(riff/shutdown!)
```

## Usage

### Defining Shortcuts

Shortcuts are defined in a tree structure. Each node is a map with a required `:context`, and optional `:binding` and `:children` keys:

```clojure
{:context :identifier          ; Unique node identifier (keyword)
 :bindings {<key> <handler>} ; Map of key combinations to handlers
 :children [<child-nodes>]}  ; Optional child nodes
```
The value for the `:binding` key should be a map of key combinations and handlers. See below for the key combination syntax and handler details.

The value for the `:children` key should be an array of other nodes with this same structure.

### Key Combination Syntax

Key combinations are strings with modifiers separated by hyphens:

```clojure
"a"                ; Simple key
"defmod-s"            ; Command/Control + S
"shift-cmd-z"      ; Shift + Command + Z
"alt-enter"        ; Alt + Enter
"ctrl-shift-p"     ; Control + Shift + P
```

### Platform-Specific Shortcuts with `kbd`

The `kbd` helper can be used to resolve a key combination in the current context:

```clojure
(require '[riff.core :refer [kbd]])

;; Single arity with defmod placeholder
(kbd "defmod-s")
;; => "cmd-s" on Mac, "ctrl-s" on Windows

;; Single arity, platform independent
(kbd "alt-s")
;; => "alt-s" on Mac, "alt-s" on Windows

;; Two arity for full control
(kbd "cmd-k" "ctrl-k")
;; => "cmd-k" on Mac, "ctrl-k" on Windows
```

### Handler Types

Handlers can be functions or data:

```clojure
;; In case of a function, it'll be called with the
;; `keydown` event that triggered the shortcut
{"cmd-s" (fn [event]
          (.preventDefault event)
          (save-file!))}

;; In case of data, like a vector, a map or a literal,
;; it'll be used as an argument for the callback.
{"cmd-s" [:app/save-file]}
```

When using data handlers, provide a callback function during initialization:

```clojure
(riff/init! shortcuts-tree
                 {:callback (fn [event-vec _event]
                             (re-frame/dispatch event-vec))})
```

### Event Bubbling

If a shortcut is not found in the active node, it bubbles up to parent nodes:

```clojure
(def tree
  {:context :root
   :bindings {"escape" root-escape-handler}
   :children
   [{:context :editor
     :bindings {"cmd-s" save-handler}}]})

;; When active node is :editor:
;; - "cmd-s" triggers save-handler (found in :editor)
;; - "escape" triggers root-escape-handler (bubbles up to :root)
```

### Context Switching

Switch between different shortcut contexts:

```clojure
;; Enable editor shortcuts
(riff/set-context! :editor)

;; Switch to sidebar
(riff/set-context! :sidebar)

;; Check current context
(riff/get-context) ; => :sidebar
```

## API Reference

### Core Functions

#### `init!`

```clojure
(init! shortcuts-tree)
(init! shortcuts-tree options)
```

Initialize the keyboard shortcuts system with a shortcuts tree.

**Options:**
- `:callback` - Function called with `[handler-vector event]` when handler is a vector

#### `set-context!`

```clojure
(set-context! id)
```

Set the currently active context in the shortcuts tree.

#### `get-context`

```clojure
(get-context)
```

Get the currently active context identifier.

#### `shutdown!`

```clojure
(shutdown!)
```

Clean up the keyboard shortcuts system. Removes event listeners and resets state.

### Utility Functions

#### `kbd`

```clojure
(kbd keystring)
(kbd mac-string win-string)
```

Helper for resolving a key combination string at runtime, based on the client operating system.

### Platform Detection

- `riff/macos?` - Boolean, true if running on macOS
- `riff/windows?` - Boolean, true if running on Windows

## Examples

### With re-frame

```clojure
(ns my-app.shortcuts
  (:require [riff.core :as riff]
            [re-frame.core :as rf]))

(def app-shortcuts
  {:context :root
   :bindings {"escape" [:ui/close-modal]
              "ctrl-q" [:app/quit]}
   :children
   [{:context :editor
     :bindings {"cmd-s" [:editor/save]
                "cmd-b" [:editor/toggle-bold]
                "cmd-i" [:editor/toggle-italic]}}]})

(defn init-shortcuts! []
  (riff/init! app-shortcuts
                   {:callback (fn [event-vec _event]
                               (rf/dispatch event-vec))}))

;; In your UI code
(defn editor-component []
  (react/useEffect
    (fn []
      (riff/set-context! :editor)
      ;; Cleanup when component unmounts
      #(riff/set-context! :root))
    #js [])
  ;; ... component body
  )
```

### Complex Tree with Nested Contexts

```clojure
(def complex-shortcuts
  {:context :app
   :bindings {"ctrl-q" [:app/quit]
              "f1" [:app/show-help]
              "ctrl-comma" [:app/show-preferences]}
   :children
   [{:context :main-window
     :bindings {"ctrl-n" [:file/new]
                "ctrl-o" [:file/open]}
     :children
     [{:context :editor
       :bindings {"ctrl-s" [:file/save]
                  "ctrl-z" [:edit/undo]
                  "ctrl-y" [:edit/redo]
                  "ctrl-f" [:search/open]}
       :children
       [{:context :find-mode
         :bindings {"escape" [:search/close]
                    "enter" [:search/find-next]
                    "shift-enter" [:search/find-prev]
                    "ctrl-f" [:search/focus-input]}}]}
      {:context :sidebar
       :bindings {"enter" [:sidebar/select]
                  "delete" [:sidebar/delete]
                  "up" [:sidebar/previous]
                  "down" [:sidebar/next]}}]}]})

;; When active node is :find-mode:
;; - "escape" closes search (handled in :find-mode)
;; - "ctrl-s" saves file (bubbles to :editor)
;; - "ctrl-q" quits app (bubbles to :app)
```

## Testing

Run tests with shadow-cljs:

```bash
npx shadow-cljs compile test
npx shadow-cljs watch test
# Open http://localhost:8021 in your browser
```

## Contributing

Contributions are welcome! Please ensure:

1. Code follows existing style
2. All tests pass
3. New features include tests
4. Documentation is updated

## License

Copyright © 2025

Distributed under the Eclipse Public License version 1.0.
