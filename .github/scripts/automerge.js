'use strict';

// Loaded only from the default branch, never from PR code. A label is an
// opt-in by a user who already has merge-capable repository access. A bot
// receipt binds that label event to a head; trusted PR workflows test that
// head, and the merge API pins it again.
const LABEL = 'automerge';
const RECEIPT = 'gesher-automerge-v1';
const OPENING = 'gesher-automerge-opening-v1';
const PASSING = new Set(['success', 'neutral', 'skipped']);
const BOT = 'github-actions[bot]';
const SHA = /^[a-f0-9]{40}$/i;
const TARGET_CHANGES = new Set(['base_ref_changed', 'automatic_base_change_succeeded']);
const INVALIDATES = new Set(['reopened', 'head_ref_force_pushed', ...TARGET_CHANGES]);

// Incomplete or excessive history requires manual handling, never a merge.
async function list(api, args, field) {
  const all = [];
  for (let page = 1; page <= 10; ++page) {
    const {data} = await api({...args, per_page: 100, page});
    const batch = field ? data[field] : data;
    if (!Array.isArray(batch) || batch.length > 100) throw new Error('invalid API listing');
    all.push(...batch);
    if (batch.length < 100 || (field && data.total_count === all.length)) return all;
  }
  throw new Error('API listing exceeds 1000 items; manual review required');
}

function latestBy(items, keyOf) {
  const latest = new Map();
  for (const item of items) {
    if (!Number.isSafeInteger(item.id) || !Number.isFinite(Date.parse(item.created_at))) {
      throw new Error('missing event/run identity');
    }
    const key = keyOf(item);
    if (key === undefined || key === null) throw new Error('missing listing key');
    const seen = latest.get(key);
    if (!seen || Date.parse(item.created_at) > Date.parse(seen.created_at) ||
        (item.created_at === seen.created_at && item.id > seen.id)) latest.set(key, item);
  }
  return [...latest.values()];
}

function hasLabel(pr) {
  return (pr.labels || []).some((l) => l.name === LABEL);
}

function appliedAfterOpening(pr, event, boundary) {
  const opened = Date.parse(pr.created_at);
  const applied = Date.parse(event.created_at);
  const author = pr.user?.id;
  const previous = Date.parse(event.priorActorApplicationAt);
  // GitHub timestamps have second precision. A creation-time label, including
  // an indistinguishable same-second addition, requires a fresh opt-in.
  // gh creates a PR and then adds its labels in a separate API operation.
  // Reject the author's first application even if opening initialization has
  // already finished; a later deliberate reapplication remains possible.
  return Number.isSafeInteger(author) && author > 0 &&
    Number.isFinite(opened) && Number.isFinite(applied) &&
    Number.isFinite(boundary) && applied > Math.max(opened, boundary) &&
    (event.actor?.id !== author ||
      (Number.isFinite(previous) && previous >= opened && previous < applied));
}

async function openingBoundary(github, owner, repo, pr) {
  const comments = await list(github.rest.issues.listComments,
    {owner, repo, issue_number: pr.number});
  let boundary = null;
  for (const c of comments) {
    if (c.user?.login !== BOT || c.user.type !== 'Bot') continue;
    const markers = (c.body || '').split('\n').filter((s) => s.startsWith('<!-- ' + OPENING + ' '));
    if (markers.length > 1) throw new Error('ambiguous opening receipt');
    if (!markers.length) continue;
    if (!c.created_at || c.created_at !== c.updated_at) throw new Error('edited opening receipt');
    if (!markers[0].endsWith(' -->')) throw new Error('malformed opening receipt');
    const value = JSON.parse(markers[0].slice(OPENING.length + 6, -4));
    if (value.repository !== pr.base.repo.id || value.number !== pr.number ||
        value.created !== pr.created_at) throw new Error('opening receipt identity mismatch');
    const at = Date.parse(value.boundary);
    if (Object.keys(value).length !== 4 || !Number.isFinite(at) ||
        at < Date.parse(pr.created_at)) throw new Error('invalid opening boundary');
    // Concurrent initializers may leave multiple receipts. Never relax the
    // most conservative recorded boundary, regardless of delivery order.
    boundary = boundary === null ? at : Math.max(boundary, at);
  }
  return boundary;
}

async function initializeOpening(github, owner, repo, pr, snapshot, event) {
  const existing = await openingBoundary(github, owner, repo, pr);
  if (existing !== null) return existing;
  const times = [pr.created_at, snapshot.updated_at];
  if (event) times.push(event.created_at);
  const parsed = times.map(Date.parse);
  if (!parsed.every(Number.isFinite)) return null;
  const boundary = Math.max(...parsed);
  const value = {repository: pr.base.repo.id, number: pr.number,
    created: pr.created_at, boundary: new Date(boundary).toISOString()};
  await github.rest.issues.createComment({owner, repo, issue_number: pr.number,
    body: 'Automerge initialized. Approval requires a later explicit label from a collaborator with merge access. ' +
      'The PR author must reapply their first automerge label before approval.\n\n<!-- ' +
      OPENING + ' ' + JSON.stringify(value) + ' -->'});
  return boundary;
}

function sameTarget(a, b) {
  return a.number === b.number && a.head.sha === b.head.sha &&
    a.head.repo?.id === b.head.repo?.id && a.head.ref === b.head.ref &&
    a.base.repo?.id === b.base.repo?.id && a.base.ref === b.base.ref;
}

async function labelEvent(github, owner, repo, number, deliveredAt) {
  const events = await list(github.rest.issues.listEvents, {owner, repo, issue_number: number});
  const relevant = events.filter((e) => INVALIDATES.has(e.event) ||
    (['labeled', 'unlabeled'].includes(e.event) && e.label?.name === LABEL));
  const last = latestBy(relevant, () => LABEL)[0];
  if (!last || last.event === 'unlabeled') return null;
  const applied = latestBy(relevant.filter((e) => e.event === 'labeled'), () => LABEL)[0];
  if (!applied) return null;
  // GitHub can stamp the PR update one second after the label event. Bind a
  // delivery only when exactly one label fits that bounded timestamp window.
  const delivered = Date.parse(deliveredAt);
  const candidates = relevant.filter((e) => e.event === 'labeled' &&
    Date.parse(e.created_at) >= delivered - 1000 && Date.parse(e.created_at) <= delivered);
  const previous = latestBy(relevant.filter((e) => e.event === 'labeled' &&
    e.id !== applied.id && e.actor?.id === applied.actor?.id &&
    Date.parse(e.created_at) < Date.parse(applied.created_at)), () => LABEL)[0];
  // A delayed delivery cannot distinguish two relabels by one actor within
  // the webhook timestamp's precision. Do not infer approval in that case.
  return {...applied, invalidated: last.event !== 'labeled',
    priorActorApplicationAt: previous?.created_at,
    deliveryMatches: Number.isFinite(delivered) && candidates.length === 1 && candidates[0].id === applied.id,
    unambiguous: relevant.filter((e) =>
      e.event === 'labeled' && e.created_at === applied.created_at).length === 1};
}

async function authorized(github, owner, repo, event) {
  if (!event?.actor?.login || !Number.isSafeInteger(event.actor.id)) return false;
  try {
    const {data} = await github.rest.repos.getCollaboratorPermissionLevel({
      owner, repo, username: event.actor.login,
    });
    // This API maps maintain to write and triage to read.
    return data.user?.id === event.actor.id &&
      (data.permission === 'admin' || data.permission === 'write');
  } catch (err) {
    if (err.status === 404) return false;
    throw err;
  }
}

function approvalFor(pr, event) {
  return {repository: pr.base.repo.id, number: pr.number, head: pr.head.sha,
    base: pr.base.ref, label: event.id, actor: event.actor.id};
}

async function approval(github, owner, repo, pr, event) {
  const comments = await list(github.rest.issues.listComments,
    {owner, repo, issue_number: pr.number});
  const expected = approvalFor(pr, event);
  const matches = [];
  for (const c of comments) {
    if (c.user?.login !== BOT || c.user.type !== 'Bot' ||
        !c.created_at || c.created_at !== c.updated_at) continue;
    const markers = (c.body || '').split('\n').filter((s) => s.startsWith('<!-- ' + RECEIPT + ' '));
    if (markers.length > 1) throw new Error('ambiguous approval receipt');
    if (!markers.length) continue;
    const marker = markers[0];
    if (!marker.endsWith(' -->')) throw new Error('malformed approval receipt');
    const value = JSON.parse(marker.slice(RECEIPT.length + 6, -4));
    if (value.repository === expected.repository && value.number === expected.number &&
        value.label === expected.label) matches.push(value);
  }
  if (!matches.length) return null;
  return matches.every((r) => Object.keys(r).length === Object.keys(expected).length &&
    Object.entries(expected).every(([k, v]) => r[k] === v)) ? expected : false;
}

async function removeLabel(github, owner, repo, pr, event, why, core) {
  // An old job must not remove a newly applied approval.
  const {data: current} = await github.rest.pulls.get({owner, repo, pull_number: pr.number});
  const now = await labelEvent(github, owner, repo, pr.number);
  if (current.state !== 'open' || current.number !== pr.number ||
      !hasLabel(current) || now?.id !== event.id) return;
  try {
    await github.rest.issues.removeLabel({owner, repo, issue_number: pr.number, name: LABEL});
  } catch (err) {
    if (err.status === 404) return;
    throw err;
  }
  await github.rest.issues.createComment({owner, repo, issue_number: pr.number,
    body: LABEL + ' label removed: ' + why});
  core.info('#' + pr.number + ': label removed (' + why + ')');
}

async function targetHead(github, owner, repo, branch) {
  const {data} = await github.rest.git.getRef({owner, repo, ref: 'heads/' + branch});
  if (data.ref !== 'refs/heads/' + branch || data.object?.type !== 'commit' ||
      !SHA.test(data.object.sha || '')) throw new Error('invalid target branch reference');
  return data.object.sha;
}

async function ciVerdict(github, owner, repo, pr, defaultBranch, baseSha) {
  const head = pr.head.sha;
  const runs = await list(github.rest.actions.listWorkflowRunsForRepo,
    {owner, repo, head_sha: head}, 'workflow_runs');
  const required = ['ci.yml'];
  if ([defaultBranch, 'feature/arm64-windows'].includes(pr.base.ref)) required.push('arm64-windows.yml');
  for (const file of required) {
    const {data: workflow} = await github.rest.actions.getWorkflow({owner, repo, workflow_id: file});
    const path = '.github/workflows/' + file;
    if (!Number.isSafeInteger(workflow.id) || workflow.path !== path || workflow.state !== 'active') {
      return 'required workflow is missing or inactive: ' + file;
    }
    const candidates = runs.filter((r) => r.workflow_id === workflow.id && r.path === path &&
      r.event === 'pull_request' && r.head_sha === head &&
      r.head_repository?.id === pr.head.repo.id && r.repository?.id === pr.base.repo.id &&
      r.head_branch === pr.head.ref);
    const selected = latestBy(candidates, () => file)[0];
    if (!selected) return 'no PR run from the required workflow: ' + file;
    const {data: run} = await github.rest.actions.getWorkflowRun({owner, repo, run_id: selected.id});
    if (run.id !== selected.id || run.workflow_id !== workflow.id || run.path !== path ||
        run.event !== 'pull_request' || run.head_sha !== head ||
        run.head_repository?.id !== pr.head.repo.id || run.repository?.id !== pr.base.repo.id ||
        run.head_branch !== pr.head.ref) return 'workflow run identity changed: ' + file;
    if (run.status !== 'completed' || run.conclusion !== 'success') {
      return file + ' has not completed successfully';
    }
    if (!Array.isArray(run.pull_requests)) return 'missing PR association data: ' + file;
    if (run.pull_requests.length && !run.pull_requests.some((p) => p.number === pr.number &&
      p.head?.sha === head && p.head.repo?.id === pr.head.repo.id &&
      p.base?.ref === pr.base.ref && p.base.repo?.id === pr.base.repo.id)) {
      return 'CI is associated with another PR/target: ' + file;
    }
    // Unlike pull_requests (live association data), referenced_workflows
    // records the immutable source GitHub resolved for this particular run.
    {
      const prefix = owner + '/' + repo + '/.github/workflows/';
      const refs = (run.referenced_workflows || []).filter((r) => r.path?.startsWith(prefix));
      const tested = new Set(refs.map((r) => r.sha));
      if (!refs.length || tested.size !== 1 || refs.some((r) =>
        !SHA.test(r.sha || '') || !r.path.endsWith('@' + r.sha) ||
        r.ref !== 'refs/pull/' + pr.number + '/merge')) {
        return 'CI has no unambiguous tested merge identity';
      }
      const mergeSha = refs[0].sha;
      const {data: commit} = await github.rest.git.getCommit({owner, repo, commit_sha: mergeSha});
      if (commit.sha !== mergeSha || commit.parents?.length !== 2 ||
          !SHA.test(commit.parents[0].sha || '') || commit.parents[1].sha !== head) {
        return 'CI has no valid tested base and approved head';
      }
      const testedBase = commit.parents[0].sha;
      if (testedBase !== baseSha) {
        // A reviewed head does not lose its passing CI merely because another
        // PR advanced the target. Prove that the tested base is still in the
        // actual target's history; live PR association data cannot prove this.
        const {data: comparison} = await github.rest.repos.compareCommits({
          owner, repo, base: testedBase, head: baseSha, per_page: 1,
        });
        if (comparison?.status !== 'ahead' ||
            comparison.base_commit?.sha !== testedBase ||
            comparison.merge_base_commit?.sha !== testedBase) {
          return 'CI tested a base outside the current target history';
        }
      }
    }
  }
  // A PR head can equal the default branch SHA. The trusted controller then
  // has its own pending check on this head; it must not wait for itself.
  const {data: controller} = await github.rest.actions.getWorkflow({
    owner, repo, workflow_id: 'automerge.yml',
  });
  if (!Number.isSafeInteger(controller.id) || controller.state !== 'active' ||
      controller.path !== '.github/workflows/automerge.yml') return 'unknown automerge controller';
  const ownRuns = runs.filter((r) => r.workflow_id === controller.id &&
    r.path === controller.path && ['pull_request_target', 'workflow_run', 'schedule'].includes(r.event));
  const ownIds = new Set(ownRuns.map((r) => r.id));
  const ownSuites = new Set(ownRuns.filter((r) => Number.isSafeInteger(r.check_suite_id))
    .map((r) => r.check_suite_id));
  const gatedRuns = runs.filter((r) => !ownIds.has(r.id));
  // A rerun keeps its original created_at. An active older run must not be
  // hidden by a newer success when selecting the latest completed outcomes.
  const active = gatedRuns.find((r) => r.status !== 'completed');
  if (active) return 'workflow still pending: ' + active.name;
  for (const run of latestBy(gatedRuns, (r) => r.workflow_id)) {
    if (!PASSING.has(run.conclusion)) {
      return 'workflow unsuccessful: ' + run.name;
    }
  }
  const checks = await list(github.rest.checks.listForRef,
    {owner, repo, ref: head, filter: 'latest'}, 'check_runs');
  const gatedChecks = checks.filter((c) => !ownSuites.has(c.check_suite?.id));
  if (!gatedChecks.length) return 'no CI check runs for the head';
  for (const check of gatedChecks) {
    if (check.status !== 'completed' || !PASSING.has(check.conclusion)) {
      return 'check still pending or unsuccessful: ' + check.name;
    }
  }
  const statuses = await list(github.rest.repos.listCommitStatusesForRef, {owner, repo, ref: head});
  for (const status of latestBy(statuses, (s) => s.context)) {
    if (status.state !== 'success') return 'commit status is not successful: ' + status.context;
  }
  return null;
}

async function tryMerge({github, core, owner, repo, number, sha, defaultBranch}) {
  const {data: pr} = await github.rest.pulls.get({owner, repo, pull_number: number});
  if (pr.state !== 'open' || pr.draft || !hasLabel(pr)) return;
  if (!SHA.test(pr.head?.sha || '') || !SHA.test(pr.base?.sha || '') ||
      !pr.head.repo?.id || !pr.base.repo?.id || (sha && pr.head.sha !== sha)) return;
  const event = await labelEvent(github, owner, repo, number);
  if (!event || !event.unambiguous || !await authorized(github, owner, repo, event)) return;
  const boundary = await initializeOpening(github, owner, repo, pr, pr, event);
  if (!appliedAfterOpening(pr, event, boundary)) return removeLabel(github, owner, repo, pr, event,
    'reapply the label after PR initialization; the author\'s first application cannot approve it', core);
  if (event.invalidated) return removeLabel(github, owner, repo, pr, event,
    'the PR changed after approval; apply the label again', core);
  const receipt = await approval(github, owner, repo, pr, event);
  if (receipt === false) return removeLabel(github, owner, repo, pr, event,
    'the approved head or target changed; apply the label again', core);
  if (!receipt) return core.info('#' + number + ': no approval for this head; reapply the label');
  // PR base.sha can retain an older snapshot. Resolve the actual target tip.
  const baseSha = await targetHead(github, owner, repo, pr.base.ref);
  const reason = await ciVerdict(github, owner, repo, pr, defaultBranch, baseSha);
  if (reason) return core.info('#' + number + ': not merging (' + reason + ')');

  // Re-read mutable PR state and the opt-in after all asynchronous CI queries.
  const latest = await labelEvent(github, owner, repo, number);
  if (latest?.id !== event.id || latest.invalidated || !latest.unambiguous ||
      !appliedAfterOpening(pr, latest, await openingBoundary(github, owner, repo, pr)) ||
      !await approval(github, owner, repo, pr, latest) ||
      !await authorized(github, owner, repo, latest)) return;
  const [finalBoundary, finalBase] = await Promise.all([
    openingBoundary(github, owner, repo, pr),
    targetHead(github, owner, repo, pr.base.ref),
  ]);
  const [{data: current}, finalEvent] = await Promise.all([
    github.rest.pulls.get({owner, repo, pull_number: number}),
    labelEvent(github, owner, repo, number),
  ]);
  if (current.state !== 'open' || current.draft || !hasLabel(current) ||
      !sameTarget(pr, current) || current.base.sha !== pr.base.sha || finalBase !== baseSha ||
      finalEvent?.id !== event.id || finalEvent.invalidated || !finalEvent.unambiguous ||
      !appliedAfterOpening(current, finalEvent, finalBoundary)) return;
  try {
    const {data: result} = await github.rest.pulls.merge({
      owner, repo, pull_number: number, sha: pr.head.sha, merge_method: 'merge',
    });
    if (result.merged !== true) return removeLabel(github, owner, repo, pr, event,
      'GitHub did not confirm the merge; inspect its merge requirements', core);
  } catch (err) {
    if ([405, 409, 422].includes(err.status)) return removeLabel(github, owner, repo, pr, event,
      'merge failed: ' + err.message, core);
    throw err;
  }
  core.info('#' + number + ': merged ' + pr.head.sha);
  // GITHUB_TOKEN merges do not trigger push workflows. Dispatch trusted main CI.
  if (pr.base.ref === defaultBranch) {
    await github.rest.actions.createWorkflowDispatch({owner, repo, workflow_id: 'ci.yml', ref: defaultBranch});
  }
}

module.exports = async ({github, context, core}) => {
  const {owner, repo} = context.repo;
  const p = context.payload;
  const defaultBranch = p.repository.default_branch;
  const args = {github, core, owner, repo, defaultBranch};
  if (context.eventName === 'pull_request_target') {
    if (p.action === 'labeled' && p.label?.name !== LABEL) return;
    const {data: pr} = await github.rest.pulls.get({owner, repo, pull_number: p.pull_request.number});
    if (pr.state !== 'open') return;
    if (!SHA.test(pr.head?.sha || '') || !SHA.test(pr.base?.sha || '') ||
        !pr.head.repo?.id || !pr.base.repo?.id) return;
    // Establish the boundary even for an unlabelled opening. If labeled is
    // delivered first, its fallback initialization invalidates that label,
    // so webhook ordering cannot turn an initial label into approval.
    if (p.action === 'opened') {
      await initializeOpening(github, owner, repo, pr, p.pull_request, null);
    }
    if (!hasLabel(pr)) return;
    const event = await labelEvent(github, owner, repo, pr.number,
      p.action === 'labeled' ? p.pull_request.updated_at : undefined);
    if (!event) return;
    // Some opening payloads already include labels with a later updated_at
    // than created_at. Reject that observed initial opt-in, but leave a label
    // applied after the opening snapshot to its own explicit delivery.
    if (p.action === 'opened' && hasLabel(p.pull_request) &&
        Date.parse(event.created_at) <= Date.parse(p.pull_request.updated_at)) {
      return removeLabel(github, owner, repo, pr, event,
        'labels included when opening a PR cannot approve it; apply the label again', core);
    }
    const boundary = await initializeOpening(github, owner, repo, pr, pr, event);
    if (!appliedAfterOpening(pr, event, boundary)) return removeLabel(github, owner, repo, pr, event,
      'reapply the label after PR initialization; the author\'s first application cannot approve it', core);
    if (event.invalidated) return removeLabel(github, owner, repo, pr, event,
      'the PR changed after approval; apply the label again', core);
    // Opening a PR never grants approval. A delayed opened event must not
    // interfere with an explicit, later label event awaiting its own delivery.
    if (p.action === 'opened') return;
    if (p.action === 'labeled') {
      if (!sameTarget(pr, p.pull_request) || !event.unambiguous ||
          !event.deliveryMatches || event.actor?.id !== p.sender?.id) {
        return core.info('#' + pr.number + ': label delivery is not uniquely bound to this head/event; reapply the label');
      }
      if (!await authorized(github, owner, repo, event)) return removeLabel(
        github, owner, repo, pr, event, 'the label must be applied by a user with write or admin access', core);
      const receipt = await approval(github, owner, repo, pr, event);
      if (receipt === false) return;
      if (!receipt) await github.rest.issues.createComment({owner, repo, issue_number: pr.number,
        body: 'Automerge approved for ' + pr.head.sha + ' into ' + pr.base.ref + '.\n\n<!-- ' +
          RECEIPT + ' ' + JSON.stringify(approvalFor(pr, event)) + ' -->'});
    } else if (p.action === 'synchronize') {
      const receipt = await approval(github, owner, repo, pr, event);
      if (receipt === false || (receipt === null &&
          Date.parse(event.created_at) < Date.parse(p.pull_request.updated_at))) {
        await removeLabel(github, owner, repo, pr, event, 'new commits were pushed; apply the label again', core);
      }
      return;
    }
    return tryMerge({...args, number: pr.number, sha: p.pull_request.head.sha});
  }
  if (context.eventName === 'workflow_run' || context.eventName === 'schedule') {
    const sha = context.eventName === 'workflow_run' ? p.workflow_run.head_sha : null;
    const prs = await list(github.rest.pulls.list, {owner, repo, state: 'open'});
    for (const pr of prs.filter((x) => hasLabel(x) && (!sha || x.head.sha === sha))) {
      try {
        await tryMerge({...args, number: pr.number, sha});
      } catch (err) {
        // Bad history for one PR must not stop the remaining candidates.
        core.warning('#' + pr.number + ': automerge blocked: ' + err.message);
      }
    }
  }
};

module.exports.LABEL = LABEL;
