# Label-driven automerge

A repository collaborator with write or admin access can apply the automerge label to opt in a specific PR head. Maintain access is reported as write by GitHub's permission API. Triage access does not authorize merging.

A label supplied when the PR is opened cannot approve it. The label event must
be strictly later than the PR's creation timestamp, including when the opener
has write or admin access. GitHub timestamps have second precision, so a label
added in that same second must also be removed and reapplied. Opening events,
label events, CI completion and scheduled sweeps enforce this rule. The opened
event also removes a label already present in its opening snapshot, even when
the snapshot's update timestamp is later than creation. A delayed opening
event preserves a later explicit replacement label.

The PR author's first automerge label application also cannot approve it,
even if the opening workflow has already finished. GitHub CLI creates the PR
and adds requested labels in separate API operations, so a label supplied with
`gh pr create --label automerge` can arrive after opening initialization. The
controller removes that first author application. An author with merge access
can deliberately reapply the label later: the complete event history must
contain a distinct, strictly earlier automerge label application by the same
account ID. Same-second applications, another account's labels and unrelated
labels do not count. An authorized collaborator other than the author can
approve with their first explicit label after initialization.

The author's identity and earlier application are checked again before merge;
an existing approval receipt cannot bypass this rule. Missing author identity
blocks approval. This is asynchronous label removal and approval enforcement,
not a restriction on GitHub's label-assignment UI or API.

The controller first records an unedited bot opening receipt, bound to the
repository, PR number, creation time and an approval boundary. A new unlabelled
PR is initialized by its opened event. If a label event arrives first, or the
PR predates this policy, initialization instead covers the current label and
removes it. Apply the label once more after initialization. This one-time
reapproval prevents webhook ordering from turning an initial label into an
approval. Concurrent initialization receipts use their latest boundary; a
late opening event cannot relax it. The boundary is checked again before merge.

The label workflow records a small, unedited github-actions bot comment containing the repository, PR, head SHA, target branch, label event ID and actor ID. A later workflow completion or scheduled sweep can use only that recorded approval. It cannot infer approval from a label that predates installation, a missed label delivery, an edited comment or ambiguous history. Remove and reapply the label if no approval was recorded. The delivered update timestamp must be zero or one second after the label timestamp, with exactly one label event in that interval and the same sender. Ambiguous same- or adjacent-second relabels require a fresh application.

Pushing a different head, force-pushing, reopening or retargeting requires a new approval. Delayed synchronize jobs re-read the current label event so they do not remove a newer approval.

## CI evidence

The controller resolves required workflows by their registered repository IDs and file paths. The required ci workflow, plus arm64-windows for main and feature/arm64-windows, must have successful pull_request runs for the approved head and source repository. A manual run or another workflow with the same display name cannot substitute.

Both required workflows call local reusable workflows. GitHub records the resolved source SHA and refs/pull/NUMBER/merge in referenced_workflows. The controller verifies that this immutable Git commit has a valid tested base and the exact approved head as its two parents. The tested base must equal the current target tip or be its ancestor, proven by GitHub's comparison API using both immutable commit SHAs. An advanced target must report ahead with the tested base as both base_commit and merge_base_commit. A target behind or diverged from the tested base, malformed comparison, or unavailable ancestry evidence blocks merging.

Ordinary target-branch advancement does not invalidate successful CI for an unchanged approved head. The reviewer should require fresh combined testing when code overlap, a shared contract, or another concrete interaction warrants it; unrelated merges do not automatically require a full CI rerun. GitHub still enforces merge conflicts and repository merge requirements.

The controller resolves the actual target through the Git reference API because PR base.sha may retain an earlier snapshot, and rereads that target after checking CI. A target change during those checks defers the merge to a later evaluation. The ARM64 source-identity call supplies immutable tested-source metadata without checking out or executing PR code in the privileged controller.

The pull_requests array in a workflow run is live association data and is empty for some fork runs. It is checked when available, but is never treated as historical proof of the tested base. Missing or mixed immutable source metadata blocks merging; legacy runs before the source-identity call was added need fresh CI.

All other workflows, check runs and commit-status contexts must finish without failure. Optional neutral/skipped checks are allowed, while the two required workflows must conclude success. Only this registered controller's trusted runs and their exact check-suite IDs are excluded, preventing the controller from waiting on itself.

## Execution and limits

The privileged job checks out only the default branch's scripts. It never downloads or executes PR code or artifacts. Permissions, approval, head, base, target, draft state and label identity are re-read before merging. GitHub's merge API pins the head and still applies repository merge rules; the controller does not bypass required reviews or branch protection. The API does not offer an atomic expected-base parameter, so repository protections or a merge queue remain the server-side control for concurrent base updates.

Workflow completion normally wakes the controller. A five-minute scheduled sweep also observes external checks and manually dispatched workflows that finish last; GitHub may delay scheduled jobs. Each API listing is bounded to 1000 items, and malformed, missing or excessive history blocks the PR for manual handling.

A confirmed merge into the default branch explicitly dispatches ci.yml because the workflow's GITHUB_TOKEN does not trigger ordinary push workflows.

The ci workflow cancels superseded runs for the same PR or branch. Its
root-specific concurrency group stays distinct from reusable workflow groups,
so a child cannot cancel its parent. ARM64 already cancels its superseded
runs independently. The approval controller keeps cancel-in-progress disabled:
new CI must not interrupt a pending approval capture or a merge operation.

Run the deterministic REST-fixture regression suite with:

    node --test .github/scripts/automerge.test.js

The suite stubs every write; it does not merge a PR, add a label or post a comment during tests.
