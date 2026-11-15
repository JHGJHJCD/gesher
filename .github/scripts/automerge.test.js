'use strict';
// node --test .github/scripts/automerge.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const run = require('./automerge.js');

const SHA = 'a'.repeat(40);
const OLD = 'b'.repeat(40);
const T = (s) => `2026-11-16T10:00:${String(s).padStart(2, '0')}Z`;

function world(over = {}) {
  const w = {
    pr: {number: 7, state: 'open', draft: false, head: {sha: SHA}, base: {ref: 'main'},
      labels: [{name: 'automerge'}]},
    events: [{event: 'labeled', label: {name: 'automerge'}, created_at: T(30)}],
    runs: [
      {id: 1, name: 'ci', event: 'pull_request', status: 'completed', conclusion: 'success', created_at: T(10)},
      {id: 2, name: 'arm64-windows', event: 'pull_request', status: 'completed', conclusion: 'success', created_at: T(10)},
    ],
    checks: [{name: 'build', status: 'completed', conclusion: 'success'},
      {name: 'e2e', status: 'completed', conclusion: 'skipped'}],
    statuses: [],
    mergeError: null,
    calls: [],
    ...over,
  };
  const rec = (name, ret) => async (args) => { w.calls.push([name, args]); return ret(args); };
  const github = {
    paginate: async (fn, args) => fn.list(args),
    rest: {
      pulls: {
        get: rec('pulls.get', () => ({data: w.pr})),
        list: {list: () => [w.pr]},
        merge: rec('merge', () => { if (w.mergeError) throw w.mergeError; return {data: {merged: true}}; }),
      },
      issues: {
        listEvents: {list: () => w.events},
        removeLabel: rec('removeLabel', () => ({})),
        createComment: rec('comment', () => ({})),
      },
      actions: {
        listWorkflowRunsForRepo: {list: (a) => (a.head_sha === w.pr.head.sha ? w.runs : [])},
        createWorkflowDispatch: rec('dispatch', () => ({})),
      },
      checks: {listForRef: {list: () => w.checks}},
      repos: {listCommitStatusesForRef: {list: () => w.statuses}},
    },
  };
  const core = {info: () => {}};
  const fire = (eventName, payload) => run({
    github, core,
    context: {eventName, repo: {owner: 'o', repo: 'r'},
      payload: {repository: {default_branch: 'main'}, ...payload}},
  });
  w.labeled = () => fire('pull_request_target',
    {action: 'labeled', label: {name: 'automerge'}, pull_request: w.pr});
  w.pushed = () => fire('pull_request_target', {action: 'synchronize', pull_request: w.pr});
  w.ciDone = (sha = SHA) => fire('workflow_run', {workflow_run: {head_sha: sha}});
  w.fire = fire;
  w.did = (name) => w.calls.filter((c) => c[0] === name);
  return w;
}

test('green CI + label merges, pinned to the head SHA, then dispatches main CI', async () => {
  const w = world();
  await w.labeled();
  assert.equal(w.did('merge').length, 1);
  assert.equal(w.did('merge')[0][1].sha, SHA);
  assert.equal(w.did('merge')[0][1].merge_method, 'merge');
  assert.deepEqual(w.did('dispatch')[0][1], {owner: 'o', repo: 'r', workflow_id: 'ci.yml', ref: 'main'});
});

test('CI completion on a labelled PR merges it', async () => {
  const w = world();
  await w.ciDone();
  assert.equal(w.did('merge').length, 1);
});

test('push after labelling removes the label and does not merge', async () => {
  const w = world();
  await w.pushed();
  assert.equal(w.did('removeLabel').length, 1);
  assert.equal(w.did('comment').length, 1);
  assert.equal(w.did('merge').length, 0);
});

test('push on an unlabelled PR does nothing', async () => {
  const w = world();
  w.pr.labels = [];
  await w.pushed();
  assert.equal(w.calls.length, 0);
});

test('label older than the latest push is dropped, not honoured', async () => {
  const w = world({events: [{event: 'labeled', label: {name: 'automerge'}, created_at: T(5)}]});
  await w.ciDone();
  assert.equal(w.did('merge').length, 0);
  assert.equal(w.did('removeLabel').length, 1);
});

test('CI that never ran blocks the merge', async () => {
  const w = world({runs: [], checks: []});
  await w.labeled();
  assert.equal(w.did('merge').length, 0);
  assert.equal(w.did('removeLabel').length, 0);
});

test('only other workflows ran (no ci run) blocks the merge', async () => {
  const w = world();
  w.runs = w.runs.filter((r) => r.name !== 'ci');
  await w.labeled();
  assert.equal(w.did('merge').length, 0);
});

for (const [status, conclusion] of [['in_progress', null], ['queued', null], ['completed', 'failure'],
  ['completed', 'cancelled'], ['completed', 'action_required'], ['completed', 'startup_failure'],
  ['completed', 'skipped']]) {
  test(`ci ${status}/${conclusion} blocks the merge`, async () => {
    const w = world();
    Object.assign(w.runs[0], {status, conclusion});
    await w.labeled();
    assert.equal(w.did('merge').length, 0);
  });
}

test('a rerun that turned green after a failure counts (latest run wins)', async () => {
  const w = world();
  Object.assign(w.runs[0], {conclusion: 'failure'});
  w.runs.push({id: 9, name: 'ci', event: 'pull_request', status: 'completed', conclusion: 'success', created_at: T(11)});
  w.events[0].created_at = T(40);
  await w.ciDone();
  assert.equal(w.did('merge').length, 1);
});

test('a failing other workflow blocks the merge', async () => {
  const w = world();
  w.runs[1].conclusion = 'failure';
  await w.ciDone();
  assert.equal(w.did('merge').length, 0);
});

test('a pending check run blocks the merge', async () => {
  const w = world();
  w.checks.push({name: 'test (windows-2025)', status: 'in_progress', conclusion: null});
  await w.ciDone();
  assert.equal(w.did('merge').length, 0);
});

test('a failed check run blocks the merge', async () => {
  const w = world();
  w.checks.push({name: 'lint', status: 'completed', conclusion: 'timed_out'});
  await w.ciDone();
  assert.equal(w.did('merge').length, 0);
});

test('no check runs at all blocks the merge', async () => {
  const w = world({checks: []});
  await w.labeled();
  assert.equal(w.did('merge').length, 0);
});

test('pending or failing commit status blocks; a later success clears it', async () => {
  for (const [states, merges] of [[['pending'], 0], [['failure'], 0], [['failure', 'success'], 1]]) {
    const w = world({statuses: states.map((state, i) => ({id: i, context: 'ext', state, created_at: T(20 + i)}))});
    await w.labeled();
    assert.equal(w.did('merge').length, merges, states.join(','));
  }
});

test('CI result for an old head does not merge the new head', async () => {
  const w = world();
  await w.ciDone(OLD);
  assert.equal(w.did('merge').length, 0);
});

test('draft, closed or unlabelled PRs are not merged', async () => {
  for (const patch of [{draft: true}, {state: 'closed'}, {labels: []}]) {
    const w = world();
    Object.assign(w.pr, patch);
    await w.labeled();
    assert.equal(w.did('merge').length, 0, JSON.stringify(patch));
  }
});

test('some other label is ignored', async () => {
  const w = world();
  await w.fire('pull_request_target', {action: 'labeled', label: {name: 'bug'}, pull_request: w.pr});
  assert.equal(w.calls.length, 0);
});

test('merge rejected (conflict / head moved) drops the label with a reason', async () => {
  const w = world({mergeError: Object.assign(new Error('Head branch was modified'), {status: 409})});
  await w.labeled();
  assert.equal(w.did('removeLabel').length, 1);
  assert.match(w.did('comment')[0][1].body, /Head branch was modified/);
  assert.equal(w.did('dispatch').length, 0);
});

test('PR into a non-default branch merges without dispatching main CI', async () => {
  const w = world();
  w.pr.base.ref = 'feature/arm64-windows';
  await w.labeled();
  assert.equal(w.did('merge').length, 1);
  assert.equal(w.did('dispatch').length, 0);
});
