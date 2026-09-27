# Gauntlet: state and open work

Read DESIGN.md first for purpose and principles.

## State (2026-09-27)

- Working end to end on a Claude plan (`:auth :subscription`) against a
  small Clojure test project. Eleven items merged, including one
  bootstrapped project setup and one item that went through seven worker
  passes.
- 34 tests pass (`clojure -M:test`).
- agent-defs is still at its scaffold content: the architecture constraints
  (`app.domain` etc.), house style and comment rules are placeholders to be
  replaced with a project's real rules.

## Open ideas, roughly by value

1. **Give reviewers a precomputed diff.** Write `git diff base..head` and the
   changed-file list to `/task/diff.patch`, so every reviewer sees the same
   diff without spending turns on git.
2. **Tell a rerunning reviewer what it rejected before.** Provide
   `/task/previous` (the commit it rejected) and its earlier findings, so it
   checks whether they were addressed. Item 9 showed test-coverage rejecting
   four times over the same point.
3. **Test-coverage rule for UI code.** Rule T2 keeps rejecting Swing code
   that cannot run in the headless container. Decide on a policy in
   `agent-defs/agents/test-coverage/prompt.md`.
4. **Replace the placeholder rules** in agent-defs with the owner's real
   constraints.
5. **Fake runtime should not merge into the real main** (it merged
   `FAKE-WORK.md` into calc once). Stop at approved, or merge to a scratch
   branch.
6. **Queue management**: `cancel <id>`, `edit <id>`, and a human `override`
   verdict recorded like any other.
7. **Rebase when main moves** instead of going to `needs_human`.
8. **Maven cache for containers**: each run downloads Clojure dependencies
   again; mount a shared cache read-only.
9. **Record the cost of interrupted runs** (currently lost, so `spent`
   undercounts).
10. **Restrict container network** to the API.
11. **Parallel items**: needs locking around item selection and merge.

## Untested against the real service

- Usage-limit detection matches Claude Code's message text by pattern; a real
  limit has not been hit yet.
- The interactive `claude setup-token` step of `bb login` was only run by the
  owner, not in tests.
