(ns gauntlet.git
  "Git operations on the project repository and on per-run clones.

  Agents never see the project repository. Each run gets a fresh clone made
  over the file:// transport, and the orchestrator fetches commits back from
  the clone. The orchestrator does not run git commands inside a clone after
  an agent has had write access to it, because the agent could have changed
  that clone's hooks or config."
  (:require [clojure.string :as str]
            [gauntlet.proc :as proc]))

(defn git! [dir & args]
  (proc/sh! (into ["git" "-C" (str dir)] args)))

(defn rev-parse
  "Returns the commit SHA for rev in repo, or nil if it does not resolve."
  [repo rev]
  (let [{:keys [exit out]} (proc/sh ["git" "-C" (str repo) "rev-parse" "--verify"
                                     "--quiet" (str rev "^{commit}")])]
    (when (zero? exit) (str/trim out))))

(defn ancestor? [repo ancestor descendant]
  (zero? (:exit (proc/sh ["git" "-C" (str repo) "merge-base" "--is-ancestor"
                          ancestor descendant]))))

(defn clone-at!
  "Clones repo into dest and checks out sha on a local branch named branch.
  The clone has no remote, so an agent cannot push from it."
  [repo dest sha branch]
  (proc/sh! ["git" "clone" "--quiet" "--no-checkout" (str "file://" repo) (str dest)])
  (git! dest "checkout" "--quiet" "-B" branch sha)
  (git! dest "remote" "remove" "origin")
  (git! dest "config" "user.name" "gauntlet worker")
  (git! dest "config" "user.email" "gauntlet@localhost"))

(defn fetch-head!
  "Fetches HEAD of the clone at src into repo as refs/heads/branch and
  returns the fetched SHA. Uses the file:// transport, which reads the clone
  as a remote rather than trusting its config."
  [repo src branch]
  (git! repo "fetch" "--quiet" "--no-tags" (str "file://" src)
        (str "+HEAD:refs/heads/" branch))
  (rev-parse repo (str "refs/heads/" branch)))

(defn- main-checked-out? [repo branch]
  (and (= "false" (git! repo "rev-parse" "--is-bare-repository"))
       (= (str "refs/heads/" branch)
          (str/trim (:out (proc/sh ["git" "-C" (str repo) "symbolic-ref" "--quiet" "HEAD"]))))))

(defn fast-forward!
  "Moves branch in repo from expected-sha to new-sha. Fails if the branch is
  no longer at expected-sha. If branch is checked out in a non-bare repo, uses
  merge --ff-only so the working tree follows."
  [repo branch expected-sha new-sha]
  (if (main-checked-out? repo branch)
    (do (when-not (= expected-sha (rev-parse repo "HEAD"))
          (throw (ex-info (str branch " moved") {:branch branch})))
        (git! repo "merge" "--quiet" "--ff-only" new-sha))
    (git! repo "update-ref" (str "refs/heads/" branch) new-sha expected-sha)))
