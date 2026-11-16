"""Distinguish a PR merge checkout from GitHub's API run/head identity."""
import json
import os
from pathlib import Path
import re
import subprocess


def source_identity(env, event=None, checkout_parents=None):
    checkout = env['GITHUB_SHA']
    assert re.fullmatch(r'[0-9a-f]{40}', checkout), 'Invalid checkout SHA'
    kind = env['GITHUB_EVENT_NAME']
    assert kind in ('push', 'workflow_dispatch', 'pull_request'), 'Unsupported source event'
    if kind != 'pull_request':
        return {'checkout_sha': checkout, 'api_head_sha': checkout,
                'head_repository_id': env['GITHUB_REPOSITORY_ID']}

    if event is None:
        event = json.loads(Path(env['GITHUB_EVENT_PATH']).read_text(encoding='utf-8-sig'))
    number = event['number']
    assert type(number) is int and number > 0, 'Invalid PR number'
    assert env['GITHUB_REF'] == f'refs/pull/{number}/merge', 'Unexpected PR ref'
    pr = event['pull_request']
    repo = pr['base']['repo']
    assert str(repo['id']) == env['GITHUB_REPOSITORY_ID'], 'Foreign PR target repository'
    assert repo['full_name'] == env['GITHUB_REPOSITORY'], 'PR target repository name mismatch'
    head_repo = pr['head']['repo']
    assert type(head_repo['id']) is int and head_repo['id'] > 0, 'Invalid PR source repository ID'
    assert re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', head_repo['full_name']), 'Invalid PR source repository name'
    assert (str(head_repo['id']) == env['GITHUB_REPOSITORY_ID']) == (head_repo['full_name'] == env['GITHUB_REPOSITORY']), 'Inconsistent PR source repository identity'
    for key in ('base', 'head'):
        assert pr[key]['ref'] == env[f'GITHUB_{key.upper()}_REF'], 'PR branch mismatch'
    head = pr['head']['sha']
    assert re.fullmatch(r'[0-9a-f]{40}', head), 'Invalid PR head SHA'
    base = pr['base']['sha']
    assert re.fullmatch(r'[0-9a-f]{40}', base), 'Invalid PR base SHA'
    # synchronize payloads can still name an older merge_commit_sha. Bind the
    # actual checkout to the payload's base/head via raw commit headers instead.
    # cat-file retains parents even when checkout used fetch-depth: 1.
    if checkout_parents is None:
        assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() == checkout, 'Checkout SHA mismatch'
        headers = subprocess.check_output(['git', 'cat-file', '-p', checkout], text=True).split('\n\n', 1)[0]
        checkout_parents = tuple(line[7:] for line in headers.splitlines() if line.startswith('parent '))
    assert tuple(checkout_parents) == (base, head), 'PR checkout parent mismatch'
    # A fork is a distinct source repository, not a different target. Consumers
    # must bind artifact metadata to this event-supplied ID and the PR head SHA.
    return {'checkout_sha': checkout, 'api_head_sha': head,
            'head_repository_id': str(head_repo['id'])}


if __name__ == '__main__':
    assert os.environ.get('GITHUB_ACTIONS') == 'true', 'CI only'
    print(json.dumps(source_identity(os.environ), sort_keys=True))
