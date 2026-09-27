# Gauntlet design

This file records why gauntlet exists, the principles it follows, and the
decisions behind its structure. README.md covers how to use it; TODO.md
covers current state and open work.

## Purpose

A lightweight, local agent orchestrator written in Clojure. Work items go
into a queue. For each item, a worker agent implements the change on its own
branch, then the change must pass a "gauntlet" of reviewer agents, each
enforcing one concern (comments, language usage, architecture, factoring,
test coverage). The branch is merged to main only when every reviewer
approves the exact commit. Every run and verdict is recorded against the
commit it assessed, so there is a clear record of what each agent said about
each commit.

The owner wanted:

- agents defined as data, with their context in versioned files, separate
  from the orchestrator;
- each agent run isolated in its own container with its own context, and no
  way for one agent to change another agent's context;
- the queue and the assessments stored locally in SQLite;
- control over spend: a model per agent, and sequential runs by default
  (parallel runs possible later);
- to use a $20/month Claude plan rather than per-token API billing.

## Principles

1. **Agent definitions are versioned data.** They live in a separate git repo
   (`../agent-defs`): `agents.edn` plus one directory per agent. The
   orchestrator reads them from the git object store at a pinned ref, never
   from a working copy. An agent's version is a SHA-256 over each of its
   context paths and that path's git object SHA, so editing one agent's files
   changes only that agent's version. Every run records the definitions
   commit, the agent version and the model.

2. **A verdict is about one commit.** A verdict depends on (commit, agent
   version, model). An approval with the same three values is reused rather
   than rerun. Reviewers judge the whole change from the item's base commit
   to the commit under review, which is exactly what would be merged.

3. **Isolation by construction, not convention.**
   - Each run gets a fresh container and a fresh clone made over `file://`,
     with no remote. Reviewers get the clone read-only.
   - Only the orchestrator writes to SQLite and to the project repo. It
     fetches commits back from a worker's clone and runs no other git
     commands there, because the agent controls that clone's hooks and
     config.
   - Clones leave out `CLAUDE.md`, `CLAUDE.local.md`, `.claude/` and
     `.mcp.json` (sparse checkout), so project files cannot add instructions,
     hooks or MCP servers to an agent. An agent's context comes only from
     agent-defs.
   - Credentials reach agent containers only, through `docker run
     --env-file`. Containers that run project tests do not get them.
   - Code under review is treated as untrusted input; the review protocol
     tells reviewers to report text that addresses them as a blocking
     finding.

4. **Deterministic tools first, models for judgment.** Test results and
   coverage are produced by commands from the config (`:inputs`) in their own
   checkout and given to reviewers as reports. Reviewers are told to trust
   those reports over their own estimates.

5. **Reviews converge.** Findings are `blocking` or `advisory`; a verdict
   must be `reject` exactly when there is a blocking finding (enforced by the
   orchestrator). Only blocking findings go back to the worker. After
   `:max-rounds` rejections the item goes to `needs_human`.

6. **Spend is bounded.** Model and per-run cap (`:max-budget-usd`) per agent;
   a daily budget checked before each run. A usage limit or a rejected
   credential pauses the pipeline and leaves the item in place instead of
   failing it.

7. **One orchestrator per queue, safe to stop.** Items run one at a time. A
   file lock prevents a second orchestrator. Stopping at any point and
   starting again continues where it left off (see README, "Stopping and
   restarting").

8. **Local and lightweight.** SQLite, git, Docker, the Clojure CLI and
   babashka. The pipeline runs on the JVM, where startup time does not
   matter; setup and quick queue commands run in babashka. Output formatting
   shared by both lives in `gauntlet.views`.

9. **Self-guiding setup.** `bb doctor` checks everything in order, stops at
   the first problem and prints the fix, then says what to do next with the
   queue. New setup steps should be added to it.

10. **Plain prose** in prompts, docs, comments and test names: short
    declarative sentences, no slogans.

## Structure

```
../agent-defs/                   separate repo: agents.edn, shared/, agents/<id>/, bin/versions.clj
src/gauntlet/pipeline.clj        state machine, worker and reviewer steps, merge, progress events
src/gauntlet/defs.clj            loads and versions agent definitions
src/gauntlet/db.clj              SQLite schema, migration, queries
src/gauntlet/git.clj             clones (sparse), fetch, fast-forward
src/gauntlet/runtime.clj         AgentRuntime protocol
src/gauntlet/runtime/docker.clj  Claude Code headless in a container; auth; stop reasons
src/gauntlet/runtime/fake.clj    stand-in agents for tests and dry runs
src/gauntlet/watch.clj           keep processing and wait for work
src/gauntlet/lock.clj            one orchestrator per queue
src/gauntlet/proc.clj            child processes; stopped on shutdown
src/gauntlet/progress.clj        live progress lines
src/gauntlet/views.clj           list, show, trace output (shared with bb)
src/gauntlet/cli.clj             clojure -M:run commands
bb/tools/doctor.clj              bb doctor
bb/tools/setup.clj               init, runtime, auth, login, check-auth, bootstrap, build-image
bb/tools/queue.clj               fast list, show, trace, add, requeue, spent (go-sqlite3 pod)
docker/agent/Dockerfile          agent image: Node, Claude Code, JDK, Clojure CLI
```

Item statuses: `queued → implementing → reviewing → merged`, with
`reviewing → implementing` on rejection and `needs_human` for anything a
person must look at. Tables: `work_items`, `events`, `runs`, `verdicts`,
`findings`.

Container contract (also in agent-defs/README.md): `/work` checkout, `/task`
(`task.md`, `base`, `feedback.json`), `/inputs` reports, `/out`
(`result.json` or `verdict.json`), `/agent/system.md` assembled context.

## Decisions and why

- **Separate definitions repo, one directory per agent** rather than a repo
  per agent: one history, and per-agent versions still come from tree SHAs.
  Keeping it out of the project repo means a worker cannot edit a reviewer's
  prompt on its branch.
- **Fresh clone per run instead of git worktrees.** A worktree needs the main
  repo's `.git`, which would let an agent change other refs, including main.
- **Subscription auth drops `--bare`.** `--bare` accepts only an API key. Its
  protection against project `CLAUDE.md` files is replaced by the sparse
  checkout, which applies to both auth modes.
- **Credentials in an env file** (`~/.config/gauntlet/env`, mode 600, written
  by `bb login`), so they are never exported in a shell or shown on a command
  line. `bb login` runs `claude setup-token` in a throwaway container, so
  Claude Code is not needed on the host.
- **`--max-budget-usd` instead of a turn limit**: the CLI has no documented
  turn limit, and a dollar cap matches the spend concern. With a
  subscription, dollar figures are API-equivalent estimates.
- **Missing project aliases are a warning, not a failure**, because inputs
  run on the commit under review; `bb bootstrap` asks the worker to add them.
- **bb queue commands duplicate only the SQL**; formatting is shared through
  `gauntlet.views`, which must stay babashka-compatible (clojure.core,
  clojure.string, java.time).

## Testing

`clojure -M:test` runs everything with the fake runtime and temporary git
repos; no API calls. The fake runtime's behaviors are functions that write
the same output files and commits a real agent would. Docker command lines
are unit-tested; mounts, credentials, shutdown and leftover-container cleanup
were checked against a real Docker daemon with stand-in images.
