# Concurrent PersonalHub work

PersonalHub uses **parallel workers + asynchronous single integration**.

## Worker phase

Independent tasks may run at the same time when each task:

1. uses its own dedicated task worktree/branch from the current remote canonical tip;
2. never writes to the canonical checkout;
3. runs branch-local host checks needed by its change;
4. pushes its branch and opens/updates its queued PR;
5. stops repository work once the PR is queued.

A worker never waits for CI/canonical merge and never acquires a repository-wide integration lease.

## Integration queue

Open queued PRs targeting the canonical branch are processed FIFO per repository by `repo-integrator`.

The integrator:

1. waits asynchronously for the current PR checks;
2. if canonical advanced, rebases the clean task branch onto the latest canonical tip;
3. pushes that refreshed task branch with `--force-with-lease` scoped to the exact observed task head;
4. waits for checks on the refreshed head;
5. merges only when the PR is mergeable and checks pass;
6. cleans the merged task branch/worktree and advances to the next PR.

Pending CI, temporary GitHub errors and canonical advances are queue states, not Codex blockers. A real rebase conflict is a semantic conflict: abort the rebase, preserve the task branch and create one focused repair task rather than guessing.

## Shared runtime resources

Git integration does not use `tools/personalhub_task_lock.py`.

Use a lease only for a resource that cannot safely be shared:

- `--resource emulator`
- `--resource pixel`
- `--resource release`
- `--resource signing`

Resources are independent. Code-only work needs no lease. The legacy no-`--resource` lock exists only for compatibility with old prompts.

## Versioning

Ordinary parallel development branches do not edit `version.txt`. The explicit final release/delivery task increments it once from latest integrated canonical immediately before the final build. This removes a high-frequency synthetic merge conflict between unrelated workers.

## Failure behavior

- CI pending/in progress: stay queued; no new prompt.
- Canonical advanced: automatic task-branch rebase and refreshed CI.
- Push race: retry only after new evidence, with force-with-lease limited to the task branch.
- Real textual/semantic rebase conflict: preserve branch and request a focused repair.
- PASS: integrator merges, cleans up and the roadmap terminal PASS is queued automatically.
