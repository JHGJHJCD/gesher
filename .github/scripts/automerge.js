'use strict';

// Merge a pull request labelled `automerge` once CI is green on its current head.
//
// Safety rules (each one blocks the merge on its own):
//   * Any push to the PR removes the label (pull_request_target: synchronize).
//   * The label must have been applied after the latest push, judged by the
//     creation time of the newest pull_request workflow run for the head.
//   * Every workflow in REQUIRED_WORKFLOWS must have a completed, successful run
//     for the exact head commit. If CI never ran, there is nothing to merge on.
//   * Every check run and commit status on the head must be finished and
//     passing (success / neutral / skipped). Anything pending means "wait".
//   * The merge call pins the head SHA, so a push that races the merge fails it.
//
// The script runs from the default branch only (pull_request_target and
// workflow_run); it never checks out or runs code from the pull request.

const LABEL = 'automerge';
const REQUIRED_WORKFLOWS = ['ci'];
const MERGE_METHOD = 'merge';
const PASSING = new Set(['success', 'neutral', 'skipped']);

function latestBy(items, keyOf) {
  const latest = new Map();
  for (const item of items) {
    const key = keyOf(item);
    const seen = latest.get(key);
    if (!seen || Date.parse(item.created_at) > Date.parse(seen.created_at) ||
        (item.created_at === seen.created_at && item.id > seen.id)) {
      latest.set(key, item);
    }
  }
  return [...latest.values()];
}

function hasLabel(pr) {
  return (pr.labels || []).some((l) => l.name === LABEL);
}

async function removeLabel(github, owner, repo, number, why, core) {
  try {
    await github.rest.issues.removeLabel({owner, repo, issue_number: number, name: LABEL});
  } catch (err) {
    if (err.status !== 404) throw err;
  }
  await github.rest.issues.createComment({
    owner, repo, issue_number: number,
    body: `\`${LABEL}\` label removed: ${why}`,
  });
  core.info(`#${number}: label removed (${why})`);
}

// Returns {ok: true} or {ok: false, wait: bool, reason}.
async function ciVerdict(github, owner, repo, sha, labelledAt) {
  const runs = await github.paginate(github.rest.actions.listWorkflowRunsForRepo,
    {owner, repo, head_sha: sha, per_page: 100});

  const prRuns = runs.filter((r) => r.event === 'pull_request' || r.event === 'pull_request_target');
  if (prRuns.length > 0 && labelledAt !== null) {
    const lastPush = Math.max(...prRuns.map((r) => Date.parse(r.created_at)));
    if (!(labelledAt > lastPush)) {
      return {ok: false, wait: false, stale: true,
        reason: 'the label was applied before the latest push; apply it again'};
    }
  }

  const latestRuns = latestBy(runs, (r) => r.name);
  for (const name of REQUIRED_WORKFLOWS) {
    const run = latestRuns.find((r) => r.name === name);
    if (!run) return {ok: false, wait: true, reason: `no '${name}' run for ${sha} yet`};
    if (run.status !== 'completed') return {ok: false, wait: true, reason: `'${name}' is ${run.status}`};
    if (run.conclusion !== 'success') {
      return {ok: false, wait: false, reason: `'${name}' concluded ${run.conclusion}`};
    }
  }
  for (const run of latestRuns) {
    if (run.status !== 'completed') return {ok: false, wait: true, reason: `'${run.name}' is ${run.status}`};
    if (!PASSING.has(run.conclusion)) {
      return {ok: false, wait: false, reason: `'${run.name}' concluded ${run.conclusion}`};
    }
  }

  const checks = await github.paginate(github.rest.checks.listForRef,
    {owner, repo, ref: sha, filter: 'latest', per_page: 100});
  if (checks.length === 0) return {ok: false, wait: true, reason: `no check runs on ${sha}`};
  for (const c of checks) {
    if (c.status !== 'completed') return {ok: false, wait: true, reason: `check '${c.name}' is ${c.status}`};
    if (!PASSING.has(c.conclusion)) {
      return {ok: false, wait: false, reason: `check '${c.name}' concluded ${c.conclusion}`};
    }
  }

  const statuses = await github.paginate(github.rest.repos.listCommitStatusesForRef,
    {owner, repo, ref: sha, per_page: 100});
  for (const s of latestBy(statuses, (x) => x.context)) {
    if (s.state === 'pending') return {ok: false, wait: true, reason: `status '${s.context}' is pending`};
    if (s.state !== 'success') return {ok: false, wait: false, reason: `status '${s.context}' is ${s.state}`};
  }
  return {ok: true};
}

async function labelledAt(github, owner, repo, number) {
  const events = await github.paginate(github.rest.issues.listEvents,
    {owner, repo, issue_number: number, per_page: 100});
  const times = events
    .filter((e) => e.event === 'labeled' && e.label && e.label.name === LABEL)
    .map((e) => Date.parse(e.created_at));
  return times.length ? Math.max(...times) : null;
}

async function tryMerge({github, core, owner, repo, number, sha, defaultBranch}) {
  const {data: pr} = await github.rest.pulls.get({owner, repo, pull_number: number});
  if (pr.state !== 'open') return core.info(`#${number}: not open`);
  if (!hasLabel(pr)) return core.info(`#${number}: no ${LABEL} label`);
  if (pr.draft) return core.info(`#${number}: draft`);
  if (sha && pr.head.sha !== sha) return core.info(`#${number}: head moved to ${pr.head.sha}, skipping ${sha}`);
  const head = pr.head.sha;

  const at = await labelledAt(github, owner, repo, number);
  if (at === null) return core.info(`#${number}: no labeled event found`);
  const verdict = await ciVerdict(github, owner, repo, head, at);
  if (verdict.stale) return removeLabel(github, owner, repo, number, verdict.reason, core);
  if (!verdict.ok) return core.info(`#${number}: not merging (${verdict.reason})`);

  try {
    await github.rest.pulls.merge({owner, repo, pull_number: number, sha: head, merge_method: MERGE_METHOD});
  } catch (err) {
    // 405 not mergeable / 409 head changed: leave a trace and drop the label.
    if (err.status === 405 || err.status === 409 || err.status === 422) {
      return removeLabel(github, owner, repo, number, `merge failed: ${err.message}`, core);
    }
    throw err;
  }
  core.info(`#${number}: merged ${head}`);

  // A merge made with GITHUB_TOKEN does not fire push workflows, so start the
  // default-branch CI explicitly (workflow_dispatch is allowed for GITHUB_TOKEN).
  if (pr.base.ref === defaultBranch) {
    await github.rest.actions.createWorkflowDispatch({owner, repo, workflow_id: 'ci.yml', ref: defaultBranch});
    core.info(`dispatched ci.yml on ${defaultBranch}`);
  }
}

module.exports = async ({github, context, core}) => {
  const {owner, repo} = context.repo;
  const p = context.payload;
  const defaultBranch = p.repository.default_branch;

  if (context.eventName === 'pull_request_target') {
    const pr = p.pull_request;
    if (p.action === 'synchronize') {
      if (hasLabel(pr)) {
        await removeLabel(github, owner, repo, pr.number,
          `new commits were pushed (${pr.head.sha.slice(0, 7)}); apply it again once you want this head merged`, core);
      }
      return;
    }
    if (p.action === 'labeled' && p.label.name !== LABEL) return;
    return tryMerge({github, core, owner, repo, number: pr.number, sha: pr.head.sha, defaultBranch});
  }

  if (context.eventName === 'workflow_run') {
    const sha = p.workflow_run.head_sha;
    const prs = await github.paginate(github.rest.pulls.list, {owner, repo, state: 'open', per_page: 100});
    for (const pr of prs.filter((x) => x.head.sha === sha && hasLabel(x))) {
      await tryMerge({github, core, owner, repo, number: pr.number, sha, defaultBranch});
    }
    return;
  }

  core.info(`ignoring event ${context.eventName}`);
};

module.exports.LABEL = LABEL;
