(ns gauntlet.defs
  "Loads agent definitions from the definitions repository at a pinned ref.

  Everything is read from the git object store, never from a working copy.
  The version calculation matches bin/versions.clj in the definitions repo."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [gauntlet.git :as git]
            [gauntlet.proc :as proc]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.security MessageDigest)))

(def Agent
  [:map
   [:role [:enum :implement :review]]
   [:order {:optional true} int?]
   [:model :string]
   [:max-budget-usd [:and number? pos?]]
   [:context-paths [:vector {:min 1} :string]]
   [:inputs {:optional true} [:vector :keyword]]])

(def Registry
  [:map [:agents [:map-of :keyword Agent]]])

(defn- object-sha [repo commit path]
  (let [{:keys [exit out]} (proc/sh ["git" "-C" (str repo) "rev-parse" "--verify"
                                     "--quiet" (str commit ":" path)])]
    (when (zero? exit) (str/trim out))))

(defn- sha256-hex [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" %) digest))))

(defn agent-version
  "SHA-256 over one line per context path: \"<path> <git object SHA>\\n\"."
  [repo commit context-paths]
  (sha256-hex (apply str (map #(str % " " (object-sha repo commit %) "\n") context-paths))))

(defn- path-files
  "Files under path at commit. prompt.md sorts first, the rest by name."
  [repo commit path]
  (let [files (str/split-lines (git/git! repo "ls-tree" "-r" "--name-only" commit "--" path))]
    (sort-by (juxt #(not (str/ends-with? % "/prompt.md")) identity)
             (remove str/blank? files))))

(defn assemble-context
  "Concatenates the agent's context files, each wrapped in a <file> tag so the
  agent can tell which text came from which file."
  [repo commit context-paths]
  (str/join "\n\n"
            (for [path  context-paths
                  file  (path-files repo commit path)]
              (str "<file path=\"" file "\">\n"
                   (git/git! repo "show" (str commit ":" file))
                   "\n</file>"))))

(defn- registry-problems [repo commit {:keys [agents]}]
  (let [implementers (filter #(= :implement (:role (val %))) agents)
        orders       (keep :order (vals agents))]
    (concat
     (when-not (= 1 (count implementers))
       [(str "expected exactly one :implement agent, found " (count implementers))])
     (when-not (= (count orders) (count (distinct orders)))
       ["reviewer :order values are not unique"])
     (for [[id a] agents
           :when (and (= :review (:role a)) (nil? (:order a)))]
       (str (name id) ": reviewer has no :order"))
     (for [[id a] agents
           path (:context-paths a)
           :when (nil? (object-sha repo commit path))]
       (str (name id) ": context path " path " does not exist")))))

(defn load-defs
  "Returns {:commit sha :agents {id agent}} where each agent also carries :id,
  :version and :context. Throws if the registry is invalid."
  [repo ref]
  (let [commit   (or (git/rev-parse repo ref)
                     (throw (ex-info (str "definitions ref not found: " ref) {:ref ref})))
        registry (edn/read-string (git/git! repo "show" (str commit ":agents.edn")))]
    (when-let [err (m/explain Registry registry)]
      (throw (ex-info (str "invalid agents.edn: " (me/humanize err)) {:commit commit})))
    (when-let [problems (seq (registry-problems repo commit registry))]
      (throw (ex-info (str "invalid agents.edn: " (str/join "; " problems)) {:commit commit})))
    {:commit commit
     :agents (into {}
                   (for [[id a] (:agents registry)]
                     [id (assoc a
                                :id id
                                :version (agent-version repo commit (:context-paths a))
                                :context (assemble-context repo commit (:context-paths a)))]))}))

(defn worker [defs]
  (first (filter #(= :implement (:role %)) (vals (:agents defs)))))

(defn reviewers [defs]
  (sort-by :order (filter #(= :review (:role %)) (vals (:agents defs)))))
