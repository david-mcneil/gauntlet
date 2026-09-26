# Gauntlet orchestrator

A local, single-process orchestrator for coding agents. Work items go into a
queue in SQLite. For each item, a worker agent implements the change on its
own branch, then a sequence of reviewer agents assesses the resulting commit.
The branch is merged to main only when every reviewer approves that exact
commit.

Agents are defined in a separate repository (`../agent-defs`), read at a
pinned ref. Every run records which definitions commit, agent version and
model produced it.

## Getting started

The setup scripts need [babashka](https://babashka.org).

```
bb init ~/projects/myproject     # create gauntlet.edn pointing at the project repo
bb doctor                        # check everything; prints the next step
```

`bb doctor` checks the tools, the config, the project repo, the agent
definitions, the runtime, the reviewer input commands, the budget and the
queue, in that order. It stops at the first problem and prints how to fix it.
When nothing is wrong, it says what to do next with the queue. Run it again
after each step.

Other tasks:

```
bb runtime fake|docker    # switch runtimes; docker also sets :user to your uid:gid
bb auth subscription|api-key   # Docker runtime: use your Claude plan or an API key
bb build-image            # build the agent image from docker/agent
bb tasks                  # list tasks
```

Working with the queue:

```
echo "Describe the change" | clojure -M:run add "Title" -
clojure -M:run run-next          # process the next item
clojure -M:run list
clojure -M:run show 1            # runs, verdicts, findings
clojure -M:run agents            # loaded agent definitions and versions
clojure -M:test                  # tests (fake runtime, no API calls)
```

With `:runtime {:type :fake}` the pipeline runs end to end with stand-in
agents that commit a placeholder change and approve. They still merge into
the project's main branch, so use a throwaway repo or a trial branch
(`:main-branch`).

## Pipeline

```
queued -> implementing -> reviewing -> merged
               ^              |
               +-- rejected --+      at most :max-rounds times
```

- **implementing**: the worker gets a fresh clone at the branch tip, the task,
  and on later rounds the blocking findings from the last rejection. The
  orchestrator fetches the clone's `HEAD` into `gauntlet/<id>` in the project
  repo. Uncommitted changes are discarded.
- **reviewing**: reviewers run in `:order`, each on its own fresh read-only
  clone of the head commit. The first rejection ends the round. A reviewer's
  earlier approval of the same commit, at the same agent version and model,
  is reused instead of running it again.
- **merge**: main is moved from the item's base commit to the approved head.
  If main has moved since the work started, the item goes to `needs_human`
  (automatic rebase is not implemented yet).
- **needs_human**: the worker reported `blocked`, made no commits, an agent
  failed or wrote invalid output, or the round limit was reached. `requeue <id>`
  returns the item to the step it failed in with a fresh round count.

A reviewer's verdict must be `reject` exactly when it has at least one
`blocking` finding; anything else is treated as invalid output.

## Isolation

- Each run gets its own directories under `data/runs/<run-id>/`: `work`
  (a clone), `task`, `inputs`, `out`, and `system.md` (the assembled context).
  Nothing else is mounted into the container.
- Clones are made over `file://` and have no remote. The orchestrator only
  fetches from a clone an agent has written to; it does not run other git
  commands in it, because the agent controls that clone's hooks and config.
- Only the orchestrator writes to SQLite and to the project repo.
- Agent definitions are read from the git object store at the configured
  ref, so neither agents nor uncommitted edits can change them.
- Checkouts leave out `CLAUDE.md` and `CLAUDE.local.md` at any depth,
  `.claude/` and `.mcp.json` (a sparse checkout; the files stay in the
  commits). Code under review therefore cannot add instructions, hooks or MCP
  servers to an agent's context. With API key auth, Claude Code also runs with
  `--bare`.

Containers have network access, which the agent needs to reach the API. They
can reach anything else on the network too.

## Spend control

- Each agent has a `:model` and a per-run `:max-budget-usd` in `agents.edn`,
  passed to Claude Code as `--model` and `--max-budget-usd`.
- Before each run, the orchestrator adds the agent's cap to today's recorded
  spend and does not start the run if the total would exceed
  `:daily-budget-usd`. The item stays where it is and resumes on the next
  `run-next`.
- Cost and token counts come from Claude Code's JSON output and are stored
  on each run.

Runs are sequential. Running items in parallel would need a lock around item
selection and the merge step.

## Docker runtime

Build the agent image (add whatever the target project needs to build and
test):

```
docker build -t gauntlet-agent docker/agent
```

Then set `:runtime` in `gauntlet.edn` (`bb runtime docker`) and choose how
Claude Code authenticates (`bb auth ...`):

- `:auth :subscription` uses a Claude plan. Create a token with
  `claude setup-token` (or `docker run -it --rm gauntlet-agent claude setup-token`)
  and export it as `CLAUDE_CODE_OAUTH_TOKEN`. Runs count against the plan's
  usage limits. When a run hits a limit the pipeline stops and leaves the item
  where it was; `run-next` after the limit resets continues from there. Dollar
  figures are API-equivalent estimates.
- `:auth :api-key` (the default) uses `ANTHROPIC_API_KEY` and is billed per
  token. Claude Code runs with `--bare`, which accepts only an API key.

The variable for the chosen auth is passed into the container by name.

- `:user` should match the owner of `data/`, so that the container can write
  to the run directories and the orchestrator can fetch from them.
- If the orchestrator itself runs inside a container that talks to the host's
  Docker daemon, set `:host-path-map` so that bind mounts use host paths.
- Reviewer inputs (`:inputs` in the config, such as test results and coverage)
  run in the same image, in their own clone of the commit under review.

The command line construction is covered by tests and the mounts have been
checked against a real Docker daemon, but a full run with Claude Code has not
been done yet.

## Project repo

`:repo` should be a clone dedicated to the orchestrator. If `main` is checked
out there, the merge uses `git merge --ff-only` so the working tree follows;
otherwise it updates the ref directly, failing if `main` has moved.

## Layout

```
src/gauntlet/cli.clj              commands
src/gauntlet/pipeline.clj         state machine, worker and reviewer steps, merge
src/gauntlet/defs.clj             loads and versions agent definitions
src/gauntlet/db.clj               SQLite schema and queries
src/gauntlet/git.clj              clones, fetch, fast-forward
src/gauntlet/runtime.clj          AgentRuntime protocol
src/gauntlet/runtime/docker.clj   Claude Code in a container
src/gauntlet/runtime/fake.clj     stand-in agents for tests and dry runs
bb/tools/doctor.clj               setup checks (bb doctor)
bb/tools/setup.clj                bb init, bb runtime, bb build-image
```

## Tables

`work_items`, `events` (status history), `runs` (one per agent execution,
with defs commit, agent version, model, input and output commit, cost),
`verdicts` (one per reviewer run, keyed by commit) and `findings`.
