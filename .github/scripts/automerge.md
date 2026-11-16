# Label-driven automerge

A repository collaborator with write or admin access can apply the automerge label to opt in a specific PR head. Maintain access is reported as write by GitHub's permission API. Triage access does not authorize merging.

The label workflow records a small, unedited github-actions bot comment containing the repository, PR, head SHA, target branch, label event ID and actor ID. A later workflow completion or scheduled sweep can use only that recorded approval. It cannot infer approval from a label that predates installation, a missed label delivery, an edited comment or ambiguous history. Remove and reapply the label if no approval was recorded. The timestamp and sender of the delivered label event must match the unique current label event; ambiguous relabels within one second require a fresh application.

Pushing a different head, force-pushing, reopening or retargeting requires a new approval. Delayed synchronize jobs re-read the current label event so they do not remove a newer approval.

## CI evidence

The controller resolves required workflows by their registered repository IDs and file paths. The required ci workflow, plus arm64-windows for main and feature/arm64-windows, must have successful pull_request runs for the approved head and source repository. A manual run or another workflow with the same display name cannot substitute.

Both required workflows call local reusable workflows. GitHub records the resolved source SHA and refs/pull/NUMBER/merge in referenced_workflows. The controller verifies that this immutable Git commit has the current base and approved head as its two parents. The ARM64 source-identity call supplies this metadata without checking out or executing PR code in the privileged controller.

The pull_requests array in a workflow run is live association data and is empty for some fork runs. It is checked when available, but is never treated as historical proof of the tested base. Missing or mixed immutable source metadata blocks merging; legacy runs before the source-identity call was added need fresh CI.

All other workflows, check runs and commit-status contexts must finish without failure. Optional neutral/skipped checks are allowed, while the two required workflows must conclude success. Only this registered controller's trusted runs and their exact check-suite IDs are excluded, preventing the controller from waiting on itself.

## Execution and limits

The privileged job checks out only the default branch's scripts. It never downloads or executes PR code or artifacts. Permissions, approval, head, base, target, draft state and label identity are re-read before merging. GitHub's merge API pins the head and still applies repository merge rules; the controller does not bypass required reviews or branch protection. The API does not offer an atomic expected-base parameter, so repository protections or a merge queue remain the server-side control for concurrent base updates.

Workflow completion normally wakes the controller. A five-minute scheduled sweep also observes external checks and manually dispatched workflows that finish last; GitHub may delay scheduled jobs. Each API listing is bounded to 1000 items, and malformed, missing or excessive history blocks the PR for manual handling.

A confirmed merge into the default branch explicitly dispatches ci.yml because the workflow's GITHUB_TOKEN does not trigger ordinary push workflows.

Run the deterministic REST-fixture regression suite with:

    node --test .github/scripts/automerge.test.js

The suite stubs every write; it does not merge a PR, add a label or post a comment during tests.
