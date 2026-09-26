(ns tools.setup
  "Tasks that create or edit gauntlet.edn. Edits go through rewrite-clj so
  comments and layout in the file are kept."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [borkdude.rewrite-edn :as r]
            [tools.conf :as conf]))

(defn- edit-config! [f]
  (spit conf/config-file (str (f (r/parse-string (slurp conf/config-file))))))

(defn init
  "Creates gauntlet.edn from the example, pointing :repo at the project."
  [[repo]]
  (cond
    (nil? repo)
    (println "Usage: bb init <path-to-project-repo>")

    (fs/exists? conf/config-file)
    (println conf/config-file "already exists. Edit it directly, or delete it and run bb init again.")

    :else
    (let [text  (slurp conf/example-file)
          ;; Drop the "Copy to gauntlet.edn" line from the example.
          text  (subs text (inc (.indexOf ^String text "\n")))
          nodes (r/assoc (r/parse-string text) :repo repo)]
      (spit conf/config-file (str nodes))
      (println "Created" conf/config-file "with :repo" (pr-str repo))
      (println "Run bb doctor to check the rest of the setup."))))

(defn set-runtime
  "Sets :runtime to the fake runtime or to Docker with this user's uid:gid."
  [[kind]]
  (cond
    (not (fs/exists? conf/config-file))
    (println "No" conf/config-file "yet. Run: bb init <path-to-project-repo>")

    (= "fake" kind)
    (do (edit-config! #(r/assoc % :runtime {:type :fake}))
        (println "Runtime set to fake: stand-in agents, no API calls."))

    (= "docker" kind)
    (let [current (:runtime (:config (conf/read-config)))
          image   (or (:image current) conf/default-image)]
      (edit-config! #(r/assoc % :runtime (array-map :type          :docker
                                                    :image         image
                                                    :auth          (or (:auth current) :api-key)
                                                    :user          (conf/uid-gid)
                                                    :env           (or (:env current) [])
                                                    :host-path-map (or (:host-path-map current) {}))))
      (println (str "Runtime set to docker (image " image ", user " (conf/uid-gid)
                    ", auth " (name (or (:auth current) :api-key)) ")."))
      (println "To use your Claude plan instead of an API key: bb auth subscription")
      (println "Run bb doctor to check what the Docker runtime still needs."))

    :else
    (println "Usage: bb runtime fake|docker")))

(defn set-auth
  "Sets how the Docker runtime authenticates Claude Code."
  [[kind]]
  (let [runtime (:runtime (:config (conf/read-config)))]
    (cond
      (not= :docker (:type runtime))
      (println "Switch to the Docker runtime first: bb runtime docker")

      (#{"subscription" "api-key"} kind)
      (do (edit-config! #(r/assoc-in % [:runtime :auth] (keyword kind)))
          (println (if (= "subscription" kind)
                     "Auth set to subscription: runs use your Claude plan via CLAUDE_CODE_OAUTH_TOKEN."
                     "Auth set to api-key: runs are billed per token via ANTHROPIC_API_KEY."))
          (println "Run bb doctor to check the token is available."))

      :else
      (println "Usage: bb auth subscription|api-key"))))

(defn build-image []
  (let [image (or (get-in (conf/read-config) [:config :runtime :image]) conf/default-image)]
    (println "Building" image "from docker/agent ...")
    (p/shell "docker" "build" "-t" image "docker/agent")))
