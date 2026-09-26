(ns tools.setup
  "Tasks that create or edit gauntlet.edn. Edits go through rewrite-clj so
  comments and layout in the file are kept."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [borkdude.rewrite-edn :as r]
            [clojure.string :as str]
            [tools.conf :as conf]
            [tools.project :as project]))

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

(defn- read-secret
  "Reads a line from the terminal without echoing it."
  [prompt]
  (if-let [console (System/console)]
    (some-> (.readPassword console "%s" (into-array Object [prompt])) String.)
    (do (print prompt) (flush) (read-line))))

(defn- read-pasted-secret
  "Reads hidden lines until an empty one and joins them without whitespace.
  A long token copied from a terminal may have been wrapped across lines."
  []
  (println "Paste it, then press Enter on an empty line (input is hidden):")
  (loop [parts []]
    (let [line (str/trim (or (read-secret "") ""))]
      (if (str/blank? line)
        (str/replace (apply str parts) #"\s" "")
        (recur (conj parts line))))))

(defn- ask-no? [prompt]
  (print prompt) (flush)
  (contains? #{"n" "no"} (str/lower-case (str/trim (or (read-line) "")))))

(defn- save-env-var!
  "Sets var=value in a docker env file, creating it readable only by the user."
  [path var value]
  (fs/create-dirs (fs/parent path))
  (when-not (fs/exists? path)
    (fs/create-file path {:posix-file-permissions "rw-------"}))
  (fs/set-posix-file-permissions path "rw-------")
  (let [others (remove #(str/starts-with? % (str var "="))
                       (str/split-lines (slurp path)))]
    (spit path (str (str/join "\n" (concat (remove str/blank? others) [(str var "=" value)])) "\n"))))

(defn login
  "Gets a credential for the Docker runtime and stores it in the env file.
  With :auth :subscription, runs claude setup-token in a throwaway container
  first. The credential never goes through gauntlet.edn or the shell history."
  [_]
  (let [{:keys [config]} (conf/read-config)
        runtime       (:runtime config)
        subscription? (= :subscription (:auth runtime))
        var           (conf/auth-var runtime)
        image         (or (:image runtime) conf/default-image)
        env-file      (or (:env-file runtime) conf/default-env-file)
        env-path      (conf/resolve-path env-file)]
    (cond
      (nil? config)
      (println "No" conf/config-file "yet. Run: bb init <path-to-project-repo>")

      (not= :docker (:type runtime))
      (println "Logging in is only needed for the Docker runtime. Run: bb runtime docker")

      :else
      (do
        (if subscription?
          (do
            (when-not (zero? (:exit (conf/sh "docker" "image" "inspect" image)))
              (build-image))
            (println "\nStep 1 of 2: sign in with your Claude account.")
            (println "Open the link below in your browser, approve access, and paste the")
            (println "code back here if asked. The token it prints at the end is what you need.\n")
            (p/shell {:continue true} "docker" "run" "-it" "--rm" image "claude" "setup-token")
            (println "\nStep 2 of 2: copy the token printed above (it starts with sk-ant-)."))
          (do
            (println "Auth is api-key: paste an API key from console.anthropic.com.")
            (println "(To use your Claude plan instead, run: bb auth subscription, then bb login.)")))
        (let [value (read-pasted-secret)]
          (if (str/blank? value)
            (println "Nothing entered; no changes made.")
            (do
              (when-not (str/starts-with? value "sk-ant-")
                (println "Note: that does not look like an Anthropic credential (expected sk-ant-...). Saved anyway."))
              (when (< (count value) 90)
                (println (str "Note: that is only " (count value) " characters; a full token is usually over 100."
                              " Part of it may be missing.")))
              (save-env-var! env-path var value)
              (edit-config! #(r/assoc-in % [:runtime :env-file] env-file))
              (println (str "Saved " var " to " env-path " (readable only by you)."))
              (println (str conf/config-file " now uses it via :env-file; nothing to export."))
              (when-not (ask-no? "Check it with a one-line request? This uses a tiny amount of your plan or credit. [Y/n] ")
                (let [{:keys [exit]} (p/shell {:continue true}
                                              (concat ["docker" "run" "--rm" "--env-file" env-path image
                                                       "claude" "--print" "Reply with the single word OK"
                                                       "--model" "claude-haiku-4-5-20251001"]
                                                      (when-not subscription? ["--bare"])))]
                  (println (if (zero? exit)
                             "The credential works."
                             "That request failed. Check the output above, then run bb login again.")))))))))))

(defn bootstrap
  "Queues a work item that asks the worker to prepare the project for the
  reviewer input commands (deps.edn, aliases, a first test)."
  [_]
  (let [{:keys [config]} (conf/read-config)
        s (project/summary)]
    (if-let [err (or (:config-error s) (get-in s [:defs :error]))]
      (println "Fix the setup first (run bb doctor):" err)
      (let [inputs  (distinct (mapcat :inputs (get-in s [:defs :agents])))
            needs   (project/needs config (conf/resolve-path (:repo config)) inputs)
            pending (first (filter #(and (= project/setup-title (:title %))
                                         (not= "merged" (:status %)))
                                   (:items s)))]
        (cond
          pending
          (println (str "Item " (:id pending) " (" project/setup-title ") is already " (:status pending)
                        ". Run: clojure -M:run run-next"))

          (not (project/setup-needed? needs))
          (println "The project already has what the reviewer input commands need. Nothing to queue.")

          :else
          (let [spec (project/setup-spec needs)
                {:keys [exit out err]} (p/shell {:in spec :out :string :err :string :continue true}
                                                "clojure" "-M:run" "add" project/setup-title "-")]
            (if (zero? exit)
              (do (println (str/trim out) "-" project/setup-title)
                  (println "\nThe worker will be asked to:\n")
                  (println spec)
                  (println "\nNext: clojure -M:run run-next"))
              (println "Could not queue the item:" err))))))))
