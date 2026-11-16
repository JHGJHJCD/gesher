"""Pure metadata checks for inline workflow validators; no API or native work."""
import ast
import contextlib
import copy
import io
import json
from pathlib import Path
import re
import textwrap
import types
import unittest


ROOT = Path(__file__).resolve().parents[2]
ACTORS = (('contributor', 'rerun-maintainer'),
          ('github-actions[bot]', 'release-maintainer'))


def inline_tree(filename):
    raw = (ROOT / '.github/workflows' / filename).read_text()
    code = textwrap.dedent(raw.split("<<'PY'\n", 1)[1].rsplit('\n          PY', 1)[0])
    return ast.parse(code)


def predicates(filename, names):
    tree = inline_tree(filename)
    scope = {'re': re}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            if isinstance(node.value, ast.Constant):
                scope[node.targets[0].id] = node.value.value
    functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in names]
    assert {node.name for node in functions} == set(names)
    exec(compile(ast.Module(body=functions, type_ignores=[]), filename, 'exec'), scope)
    return scope


class WorkflowActorMetadata(unittest.TestCase):
    def assert_rejected(self, callback):
        with self.assertRaises((AssertionError, KeyError, RuntimeError)):
            callback()

    def test_fonts_inline_run_identity(self):
        for component, branch in (('fonts-debugger-preflight', 'wip/fonts-debugger-preflight'),
                                  ('fonts-resource', 'wip/fonts-resource-parser')):
            policy = predicates(component + '-trusted-source.yml', ('require', 'run_identity'))
            repository = dict(full_name='gesher-runtime/gesher', id=1426153186)
            run = dict(id=123, run_attempt=2, head_sha='1' * 40, name=component + '-component',
                       path=policy['CALLER'], head_branch=branch, event='workflow_dispatch',
                       repository=dict(repository), head_repository=dict(repository),
                       actor=dict(login='contributor'), triggering_actor=dict(login='rerun-maintainer'))
            check = lambda value: policy['run_identity'](value, '1' * 40, '123', '2', 'workflow_dispatch')
            for actor, trigger in ACTORS:
                check(dict(run, actor=dict(login=actor), triggering_actor=dict(login=trigger)))
            for key in ('id', 'run_attempt', 'head_sha', 'name', 'path', 'head_branch', 'event'):
                bad = dict(run); bad[key] = 'invalid'
                with self.subTest(component=component, field=key):
                    self.assert_rejected(lambda: check(bad))
            for key in ('repository', 'head_repository'):
                for field in ('id', 'full_name'):
                    bad = copy.deepcopy(run); bad[key][field] = 'invalid'
                    self.assert_rejected(lambda: check(bad))
            for key in ('actor', 'triggering_actor'):
                for value in (None, {}, {'login': ''}, {'login': 123}):
                    bad = dict(run); bad[key] = value
                    self.assert_rejected(lambda: check(bad))

    def test_native_off_inline_authority(self):
        policy = predicates('pthread-native-off-receiver-trusted-source.yml', ('authority_env', 'authority_run'))
        source = '1' * 40
        guard = policy['REPO'] + '/' + policy['TRUST'] + '@' + '2' * 40
        env = dict(GITHUB_ACTIONS='true', GITHUB_REPOSITORY=policy['REPO'],
                   GITHUB_REPOSITORY_ID=policy['RID'], GITHUB_REF=policy['REF'],
                   GITHUB_WORKFLOW=policy['NAME'], GITHUB_JOB='verify',
                   GITHUB_WORKFLOW_REF=policy['REPO'] + '/' + policy['CALLER'] + '@' + policy['REF'],
                   GITHUB_SHA=source, GITHUB_WORKFLOW_SHA=source, GITHUB_EVENT_NAME='workflow_dispatch',
                   GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2',
                   GITHUB_ACTOR='contributor', GITHUB_TRIGGERING_ACTOR='rerun-maintainer',
                   **{policy['VARIABLE']: source})
        repository = dict(full_name=policy['REPO'], id=int(policy['RID']))
        run = dict(id=123, run_attempt=2, head_sha=source, head_branch=policy['REF'].removeprefix('refs/heads/'),
                   path=policy['CALLER'], name=policy['NAME'], event='workflow_dispatch',
                   repository=dict(repository), head_repository=dict(repository),
                   actor=dict(login='contributor'), triggering_actor=dict(login='rerun-maintainer'),
                   referenced_workflows=[dict(path=guard, sha='2' * 40)])
        check = lambda current, value: policy['authority_run'](current, value, guard)
        for actor, trigger in ACTORS:
            check(dict(env, GITHUB_ACTOR=actor, GITHUB_TRIGGERING_ACTOR=trigger),
                  dict(run, actor=dict(login=actor), triggering_actor=dict(login=trigger)))
        for key in env:
            bad = dict(env); bad[key] = '' if key in ('GITHUB_ACTOR', 'GITHUB_TRIGGERING_ACTOR') else 'invalid'
            with self.subTest(field=key):
                self.assert_rejected(lambda: check(bad, run))
        for key in ('id', 'run_attempt', 'head_sha', 'head_branch', 'path', 'name', 'event', 'referenced_workflows'):
            bad = dict(run); bad[key] = [] if key == 'referenced_workflows' else 'invalid'
            self.assert_rejected(lambda: check(env, bad))
        for key in ('repository', 'head_repository'):
            for field in ('id', 'full_name'):
                bad = copy.deepcopy(run); bad[key][field] = 'invalid'
                self.assert_rejected(lambda: check(env, bad))
        for key in ('actor', 'triggering_actor'):
            for value in (None, {}, {'login': ''}, {'login': 123}):
                bad = dict(run); bad[key] = value
                self.assert_rejected(lambda: check(env, bad))

    def test_first_link_inline_authority(self):
        filename = 'pthread-first-link-trusted-source.yml'
        tree = inline_tree(filename)
        start = next(i for i, node in enumerate(tree.body) if isinstance(node, ast.Assign)
                     and any(isinstance(target, ast.Name) and target.id == 'env' for target in node.targets))
        end = next(i for i, node in enumerate(tree.body) if isinstance(node, ast.FunctionDef) and node.name == 'git')
        code = compile(ast.Module(body=tree.body[start:end], type_ignores=[]), filename, 'exec')
        constants = predicates(filename, ())
        source = '1' * 40
        env = dict(GITHUB_ACTIONS='true', GITHUB_REPOSITORY=constants['REPO'],
                   GITHUB_REPOSITORY_ID=constants['RID'], GITHUB_REF=constants['REF'],
                   GITHUB_WORKFLOW=constants['NAME'], GITHUB_JOB='verify',
                   GITHUB_WORKFLOW_REF=constants['REPO'] + '/' + constants['CALLER_PATH'] + '@' + constants['REF'],
                   GITHUB_SHA=source, GITHUB_WORKFLOW_SHA=source, PTHREAD_FIRST_LINK_CALLER_COMMIT=source,
                   GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2',
                   GITHUB_ACTOR='contributor', GITHUB_TRIGGERING_ACTOR='rerun-maintainer')
        repository = dict(full_name=constants['REPO'], id=int(constants['RID']))
        run = dict(id=123, run_attempt=2, head_sha=source, head_branch='wip/pthread-first-link',
                   path=constants['CALLER_PATH'], name=constants['NAME'], event='workflow_dispatch',
                   repository=dict(repository), head_repository=dict(repository),
                   actor=dict(login='contributor'), triggering_actor=dict(login='rerun-maintainer'))

        def check(current, value):
            def api(argv, **kwargs):
                self.assertEqual(argv, ['gh', 'api', 'repos/gesher-runtime/gesher/actions/runs/' + current['GITHUB_RUN_ID']])
                return json.dumps(value)
            scope = dict(constants, os=types.SimpleNamespace(environ=current), json=json,
                         subprocess=types.SimpleNamespace(check_output=api))
            with contextlib.redirect_stdout(io.StringIO()):
                exec(code, scope)

        for actor, trigger in ACTORS:
            check(dict(env, GITHUB_ACTOR=actor, GITHUB_TRIGGERING_ACTOR=trigger),
                  dict(run, actor=dict(login=actor), triggering_actor=dict(login=trigger)))
        for key in env:
            bad = dict(env); bad[key] = '' if key in ('GITHUB_ACTOR', 'GITHUB_TRIGGERING_ACTOR') else 'invalid'
            self.assert_rejected(lambda: check(bad, run))
        for key in ('id', 'run_attempt', 'head_sha', 'head_branch', 'path', 'name', 'event'):
            bad = dict(run); bad[key] = 'invalid'
            self.assert_rejected(lambda: check(env, bad))
        for key, field in (('repository', 'id'), ('repository', 'full_name'), ('head_repository', 'id')):
            bad = copy.deepcopy(run); bad[key][field] = 'invalid'
            self.assert_rejected(lambda: check(env, bad))
        for key in ('actor', 'triggering_actor'):
            for value in (None, {}, {'login': ''}, {'login': 123}):
                bad = dict(run); bad[key] = value
                self.assert_rejected(lambda: check(env, bad))


if __name__ == '__main__':
    unittest.main()
