#!/usr/bin/env python3
"""Read-only Project Capsule v1 FAST/FULL preflight."""
from __future__ import annotations

import argparse
import fnmatch
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys

FIELDS = ('identity', 'scope', 'repository', 'architecture', 'dependencies', 'data', 'runtime', 'devices', 'commands', 'safety', 'acceptance', 'references', 'freshness', 'artifacts', 'validation_hooks', 'change_verification')
COMMANDS = ('setup', 'build', 'test_targeted', 'test_full', 'lint', 'deploy_install', 'smoke')


def schema_errors(value: object, schema: dict, root: dict, path: str = '$') -> list[str]:
    if '$ref' in schema:
        target = root
        for part in schema['$ref'].removeprefix('#/').split('/'):
            target = target[part]
        return schema_errors(value, target, root, path)
    if 'oneOf' in schema:
        return [] if sum(not schema_errors(value, option, root, path) for option in schema['oneOf']) == 1 else [f'{path}: oneOf']
    errors = []
    expected = schema.get('type')
    types = {'object': dict, 'array': list, 'string': str, 'integer': int, 'boolean': bool}
    if expected and (not isinstance(value, types[expected]) or (expected == 'integer' and isinstance(value, bool))):
        return [f'{path}: expected {expected}']
    if 'const' in schema and value != schema['const']:
        errors.append(f'{path}: const')
    if isinstance(value, dict):
        for key in schema.get('required', []):
            if key not in value:
                errors.append(f'{path}.{key}: required')
        props = schema.get('properties', {})
        for key, item in value.items():
            if key in props:
                errors.extend(schema_errors(item, props[key], root, f'{path}.{key}'))
            elif isinstance(schema.get('additionalProperties'), dict):
                errors.extend(schema_errors(item, schema['additionalProperties'], root, f'{path}.{key}'))
            elif schema.get('additionalProperties') is False:
                errors.append(f'{path}.{key}: unknown')
    if isinstance(value, list):
        if len(value) < schema.get('minItems', 0):
            errors.append(f'{path}: minItems')
        if 'items' in schema:
            for index, item in enumerate(value):
                errors.extend(schema_errors(item, schema['items'], root, f'{path}[{index}]'))
    if isinstance(value, str):
        if len(value) < schema.get('minLength', 0):
            errors.append(f'{path}: minLength')
        if 'pattern' in schema and not re.search(schema['pattern'], value):
            errors.append(f'{path}: pattern')
    if isinstance(value, int) and not isinstance(value, bool) and value < schema.get('minimum', value):
        errors.append(f'{path}: minimum')
    return errors


def git(repo: Path, *args: str) -> tuple[int, str]:
    p = subprocess.run(['git', '-C', str(repo), *args], text=True, capture_output=True, check=False)
    return p.returncode, p.stdout.strip()


def inside(repo: Path, relative: str, *, must_exist: bool = True) -> bool:
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        return False
    try:
        target = (repo / relative).resolve()
        target.relative_to(repo.resolve())
        return not must_exist or target.exists()
    except ValueError:
        return False


def check(repo: Path, mode: str, db: Path, changed_files: list[str], timeout: int) -> dict:
    checks: list[dict] = []
    def add(name: str, passed: bool | None, detail: str = '') -> None:
        checks.append({'check': name, 'state': 'pass' if passed is True else 'fail' if passed is False else 'unverified', 'detail': detail})

    manifest_path = next((repo / name for name in ('project-capsule.yaml', 'project-capsule.yml', 'project-capsule.json') if (repo / name).is_file()), None)
    if manifest_path is None:
        add('manifest', False, 'root manifest absent')
        return {'schema': 'c2.project-capsule-check.v1', 'mode': mode, 'status': 'FAIL', 'checks': checks}
    try:
        if manifest_path.suffix == '.json':
            manifest = json.loads(manifest_path.read_text())
        else:
            import yaml
            manifest = yaml.safe_load(manifest_path.read_text())
        if not isinstance(manifest, dict):
            raise ValueError('root must be an object')
    except (OSError, ValueError, ImportError) as exc:
        add('manifest', False, str(exc))
        return {'schema': 'c2.project-capsule-check.v1', 'mode': mode, 'status': 'FAIL', 'checks': checks}
    add('version', manifest.get('schema_version') == 'c2.project-capsule.v1')
    contract = json.loads((Path(__file__).resolve().parent.parent / 'docs/project-capsule/schema-v1.json').read_text())
    errors = schema_errors(manifest, contract, contract)
    add('json_schema', not errors, '; '.join(errors[:10]))
    add('required_fields', all(k in manifest for k in FIELDS), ','.join(k for k in FIELDS if k not in manifest))
    add('known_fields', set(manifest) == set(FIELDS) | {'schema_version'})
    if any(c['state'] == 'fail' for c in checks):
        return {'schema': 'c2.project-capsule-check.v1', 'mode': mode, 'status': 'FAIL', 'checks': checks}

    identity = manifest['identity']
    add('identity_shape', isinstance(identity, dict) and isinstance(identity.get('project_id'), int) and identity['project_id'] > 0 and all(isinstance(identity.get(k), str) and identity[k] for k in ('slug', 'repository_id')))
    if db.is_file() and checks[-1]['state'] == 'pass':
        try:
            conn = sqlite3.connect(f'file:{db}?mode=ro', uri=True)
            row = conn.execute('select p.slug,r.repository_id,r.worktree_path from projects p join repositories r on r.project_id=p.project_id where p.project_id=? and r.repository_id=?', (identity['project_id'], identity['repository_id'])).fetchone()
            conn.close()
            add('megavault_identity', bool(row and row[0] == identity['slug'] and row[2] == manifest['repository'].get('canonical_workdir')), 'registry identity/workdir')
        except sqlite3.Error as exc:
            add('megavault_identity', False, type(exc).__name__)
    else:
        add('megavault_identity', None, 'MegaVault DB unavailable')

    paths = []
    arch = manifest['architecture']
    for key in ('entrypoints', 'important_paths'):
        paths.extend(arch.get(key, []) if isinstance(arch, dict) and isinstance(arch.get(key), list) else [])
    artifacts = manifest['artifacts']
    paths.extend(artifacts.values() if isinstance(artifacts, dict) else [])
    add('local_paths', bool(paths) and all(inside(repo, p) for p in paths), 'entrypoints, important paths, artifacts')
    commands = manifest['commands']
    shape = isinstance(commands, dict) and all(k in commands for k in COMMANDS + ('verify',))
    if shape:
        for key in COMMANDS:
            item = commands[key]
            if not isinstance(item, dict) or not (('not_applicable' in item and isinstance(item['not_applicable'], str) and item['not_applicable']) or (isinstance(item.get('argv'), list) and item['argv'] and all(isinstance(x, str) and x for x in item['argv']) and inside(repo, item.get('cwd', '.')))):
                shape = False
        verify = commands['verify']
        shape = shape and isinstance(verify, dict) and all(isinstance(verify.get(m), dict) and isinstance(verify[m].get('argv'), list) and verify[m]['argv'] and inside(repo, verify[m].get('cwd', '.')) for m in ('fast', 'full'))
    add('commands', shape)
    sections = ('scope', 'architecture', 'data', 'runtime', 'safety', 'references')
    add('sections', all(isinstance(manifest[k], dict) and manifest[k] for k in sections) and all(isinstance(manifest[k], list) for k in ('dependencies', 'devices', 'acceptance', 'validation_hooks', 'change_verification')))
    add('acceptance', isinstance(manifest['acceptance'], list) and bool(manifest['acceptance']) and all(isinstance(x, str) and x for x in manifest['acceptance']))

    freshness = manifest['freshness']
    commit = freshness.get('reviewed_commit') if isinstance(freshness, dict) else None
    watched = freshness.get('watch_paths') if isinstance(freshness, dict) else None
    valid_watch = isinstance(watched, list) and bool(watched) and all(inside(repo, p, must_exist=False) for p in watched)
    rc, head = git(repo, 'rev-parse', 'HEAD')
    ancestor = isinstance(commit, str) and len(commit) == 40 and git(repo, 'merge-base', '--is-ancestor', commit, 'HEAD')[0] == 0
    add('freshness_ref', bool(valid_watch and rc == 0 and ancestor), str(commit))
    if valid_watch and ancestor:
        _, changed = git(repo, 'diff', '--name-only', commit, 'HEAD', '--', *watched)
        _, dirty = git(repo, 'status', '--porcelain', '--', *watched)
        add('freshness', not bool(changed or dirty), 'watched paths changed' if changed or dirty else f'reviewed through {head}')

    mappings = manifest['change_verification']
    for path in changed_files:
        matched = isinstance(mappings, list) and any(isinstance(m, dict) and isinstance(m.get('paths'), list) and any(isinstance(p, str) and fnmatch.fnmatchcase(path, p) for p in m['paths']) and isinstance(m.get('commands'), list) and bool(m['commands']) and all(c in COMMANDS for c in m['commands']) for m in mappings)
        add(f'change_verification:{path}', matched)

    hooks = manifest['validation_hooks']
    hook_ids = set()
    if isinstance(hooks, list):
        for hook in hooks:
            valid = isinstance(hook, dict) and isinstance(hook.get('id'), str) and hook['id'] not in hook_ids and isinstance(hook.get('argv'), list) and bool(hook['argv']) and all(isinstance(x, str) and x for x in hook['argv']) and isinstance(hook.get('safe'), bool) and inside(repo, hook.get('cwd', '.'))
            add('hook_declaration', valid, str(hook.get('id') if isinstance(hook, dict) else 'invalid'))
            if not valid:
                continue
            hook_ids.add(hook['id'])
            if mode == 'FULL' and hook['safe']:
                try:
                    p = subprocess.run(hook['argv'], cwd=repo / hook.get('cwd', '.'), timeout=timeout, check=False, capture_output=True, env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
                    add(f"hook:{hook['id']}", p.returncode == 0, f'exit={p.returncode}')
                except (OSError, subprocess.TimeoutExpired) as exc:
                    add(f"hook:{hook['id']}", False, type(exc).__name__)
    status = 'FAIL' if any(c['state'] == 'fail' for c in checks) else 'UNVERIFIED' if any(c['state'] == 'unverified' for c in checks) else 'PASS'
    return {'schema': 'c2.project-capsule-check.v1', 'mode': mode, 'status': status, 'checks': checks}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, default=Path('.'))
    parser.add_argument('--mode', choices=('FAST', 'FULL'), default='FAST')
    parser.add_argument('--megavault-db', type=Path, default=Path('/home/daniele/MegaVault/megavault.sqlite'))
    parser.add_argument('--changed-file', action='append', default=[])
    parser.add_argument('--timeout', type=int, default=300)
    args = parser.parse_args()
    report = check(args.repo.resolve(), args.mode, args.megavault_db, args.changed_file, args.timeout)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
