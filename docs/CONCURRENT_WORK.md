# Concurrent PersonalHub work

PersonalHub separates **implementation** from **integration**.

## Implementation

Independent tasks may run at the same time only when each task:

1. works on its own dedicated branch;
2. never writes to the canonical/default branch;
3. runs only branch-local host checks during implementation;
4. pushes its branch and opens a PR when ready;
5. does not merge while still acting as the implementation worker.

A worker must not acquire the PersonalHub task lock merely to edit code on its isolated branch. After the PR is ready, the same Codex session may immediately transition into the integrating role: it must acquire the lease first, refresh the canonical branch once, perform the semantic review below, and only then merge. This preserves one-at-a-time integration without forcing a second Codex session for every task.

## Integration queue

Open PRs targeting the canonical branch are the queue. Integration is deliberately one-at-a-time.

The integrating Codex session:

1. acquires `tools/personalhub_task_lock.py`;
2. chooses one ready PR;
3. refreshes the canonical branch once;
4. runs `tools/personalhub_integration_context.py --branch <branch>` for bounded branch facts;
5. reads the relevant diff/code and checks semantic interaction with changes already present in the latest canonical branch;
6. resolves conflicts or necessary compatibility fixes in the candidate branch, never by guessing;
7. runs the smallest tests/compile gates that cover the interaction;
8. merges only after semantic review and gates PASS;
9. deletes the merged branch;
10. releases the lock.

A Git merge that reports no textual conflict is **not** sufficient approval. The helper never makes a merge-safety decision.

## Shared QA and release

Shared emulator/device QA and release also require the same lease, because they mutate shared runtime state. Branch-local host tests do not.

## Failure behavior

- Textual conflict: Codex resolves it with the task intent and current code in view.
- No textual conflict but semantic incompatibility: Codex fixes the candidate branch and reruns only relevant gates.
- Ambiguous interaction or required out-of-scope redesign: BLOCKED; do not merge.
- PASS: merge/push, delete the branch, release the lease, then take the next PR.
