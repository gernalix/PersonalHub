"""Capsule input-boundary regressions; all registries/repos/secrets are fixtures."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[1]
TOOL = ROOT / 'tools/project_capsule.py'
SPEC = importlib.util.spec_from_file_location('capsule_security_subject', TOOL)
SUBJECT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUBJECT)
CANARY = 'FAKE_ONLY_7ZQ9'
NAMES = ('project-capsule.yaml', 'project-capsule.yml', 'project-capsule.json')


class CapsuleSecurityTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='capsule-security-')
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / 'project'
        self.repo.mkdir()
        (self.repo / 'app.py').write_text('# synthetic fixture\n')
        env = {'PATH': os.defpath, 'HOME': str(self.base / 'home'),
               'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull}
        for args in (('init', '-q', '-b', 'main'), ('add', 'app.py'),
                     ('-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
                      'commit', '-q', '-m', 'synthetic fixture')):
            subprocess.run(['git', '-C', str(self.repo), *args], env=env, check=True, capture_output=True)
        head = subprocess.check_output(['git', '-C', str(self.repo), 'rev-parse', 'HEAD'], env=env, text=True).strip()
        self.manifest = copy.deepcopy(yaml.safe_load((ROOT / 'project-capsule.yaml').read_text()))
        self.manifest['repository']['canonical_workdir'] = str(self.repo)
        self.manifest['architecture']['entrypoints'] = ['app.py']
        self.manifest['architecture']['important_paths'] = ['app.py']
        self.manifest['artifacts'] = {key: 'app.py' for key in self.manifest['artifacts']}
        self.manifest['freshness'] = {'reviewed_commit': head, 'watch_paths': ['app.py']}
        commands = {key: {'not_applicable': 'synthetic fixture'} for key in SUBJECT.COMMANDS}
        commands['verify'] = {mode: {'argv': [sys.executable, '-c', 'pass'], 'cwd': '.'}
                              for mode in ('fast', 'full')}
        self.manifest['commands'] = commands
        self.marker = self.repo / 'full-hook-marker'
        self.manifest['validation_hooks'] = [{
            'id': 'synthetic-hook', 'argv': [sys.executable, '-c',
                'from pathlib import Path; Path(' + repr(str(self.marker)) + ').write_text("ran")'],
            'cwd': '.', 'safe': True,
        }]
        self.manifest['change_verification'] = [{'paths': ['app.py'], 'commands': ['test_full']}]
        self.db = self.base / 'registry.sqlite'
        identity = self.manifest['identity']
        with sqlite3.connect(self.db) as conn:
            conn.executescript('CREATE TABLE projects(project_id INTEGER,slug TEXT); '
                               'CREATE TABLE repositories(project_id INTEGER,repository_id TEXT,worktree_path TEXT);')
            conn.execute('INSERT INTO projects VALUES(?,?)', (identity['project_id'], identity['slug']))
            conn.execute('INSERT INTO repositories VALUES(?,?,?)',
                         (identity['project_id'], identity['repository_id'], str(self.repo)))

    def write_manifest(self, name=NAMES[0]):
        for candidate in NAMES:
            (self.repo / candidate).unlink(missing_ok=True)
        text = json.dumps(self.manifest) if name.endswith('.json') else yaml.safe_dump(self.manifest)
        path = self.repo / name
        path.write_text(text)
        return path

    def check(self, mode='FAST'):
        return SUBJECT.check(self.repo, mode, self.db, ['app.py'], 5)

    def assert_safe_failure(self, report):
        self.assertEqual('FAIL', report['status'], report)
        self.assertEqual('c2.project-capsule-check.v1', report['schema'])
        self.assertTrue(any(row['check'] == 'manifest' and row['state'] == 'fail' for row in report['checks']))
        self.assertNotIn(CANARY, json.dumps(report))
        self.assertFalse(self.marker.exists())

    def test_regular_manifests_keep_fast_read_only_and_mapping_checks(self):
        before = self.db.read_bytes()
        for name in NAMES:
            with self.subTest(name=name):
                self.write_manifest(name)
                self.assertEqual('PASS', self.check()['status'])
                self.assertFalse(self.marker.exists())
                self.assertEqual('FAIL', SUBJECT.check(self.repo, 'FAST', self.db, ['unmapped.file'], 5)['status'])
        self.assertEqual(before, self.db.read_bytes())

    def test_full_still_runs_the_explicit_synthetic_hook(self):
        self.write_manifest()
        self.assertEqual('PASS', self.check('FULL')['status'])
        self.assertEqual('ran', self.marker.read_text())

    def test_contained_manifest_links_preserve_selected_filename_parser(self):
        for name in NAMES:
            with self.subTest(name=name):
                path = self.write_manifest(name)
                target = self.repo / 'manifest-data.txt'
                target.write_bytes(path.read_bytes())
                path.unlink()
                path.symlink_to(target.name)
                self.assertEqual('PASS', self.check()['status'])

    def test_outside_links_fail_before_any_content_read_for_all_names_and_modes(self):
        private = self.base / 'private.ini'
        private.write_text('[authentication]\npassword = ' + CANARY + '\n')
        private.chmod(0o600)
        original = Path.read_text
        def guarded(path, *args, **kwargs):
            if path.resolve() == private:
                raise AssertionError('outside content was read')
            return original(path, *args, **kwargs)
        for name in NAMES:
            for mode in ('FAST', 'FULL'):
                with self.subTest(name=name, mode=mode):
                    self.write_manifest(name).unlink()
                    (self.repo / name).symlink_to(private)
                    with patch.object(Path, 'read_text', guarded):
                        self.assert_safe_failure(self.check(mode))

    def test_unsafe_primary_does_not_fall_through_to_valid_json(self):
        self.write_manifest(NAMES[2])
        target = self.base / 'outside.json'
        target.write_text(json.dumps(self.manifest))
        (self.repo / NAMES[0]).symlink_to(target)
        self.assert_safe_failure(self.check())

    def test_yaml_errors_never_reflect_source_lines(self):
        for content in ('[authentication]\npassword = ' + CANARY + '\n',
                        '!unsupported ' + CANARY + '\n', '{broken: [' + CANARY):
            with self.subTest(content_kind=content.split()[0]):
                self.write_manifest().write_text(content)
                self.assert_safe_failure(self.check())

    def test_json_and_encoding_errors_are_safe(self):
        self.write_manifest(NAMES[2]).write_text('{"secret":' + CANARY)
        self.assert_safe_failure(self.check())
        self.write_manifest().write_bytes(b'\xff' + CANARY.encode())
        self.assert_safe_failure(self.check())

    def test_dangling_and_looping_links_fail_without_fallback(self):
        for target in ('missing-file', NAMES[0]):
            with self.subTest(target=target):
                self.write_manifest(NAMES[2])
                (self.repo / NAMES[0]).symlink_to(target)
                self.assert_safe_failure(self.check())

    def test_real_cli_returns_only_json_for_valid_and_unsafe_manifests(self):
        for kind in ('valid', 'outside', 'malformed'):
            with self.subTest(kind=kind):
                path = self.write_manifest()
                if kind == 'outside':
                    target = self.base / 'private.ini'
                    target.write_text('[authentication]\npassword = ' + CANARY + '\n')
                    path.unlink()
                    path.symlink_to(target)
                elif kind == 'malformed':
                    path.write_text('[authentication]\npassword = ' + CANARY + '\n')
                run = subprocess.run([sys.executable, str(TOOL), '--repo', str(self.repo),
                                      '--mode', 'FAST', '--megavault-db', str(self.db)],
                                     capture_output=True, text=True)
                self.assertEqual(0 if kind == 'valid' else 1, run.returncode)
                report = json.loads(run.stdout)
                self.assertEqual('', run.stderr)
                self.assertNotIn(CANARY, run.stdout + run.stderr)
                if kind == 'valid':
                    self.assertEqual('PASS', report['status'])
                else:
                    self.assert_safe_failure(report)

    def test_reference_resolution_errors_fail_closed(self):
        (self.repo / 'loop').symlink_to('loop')
        self.assertFalse(SUBJECT.inside(self.repo, 'loop'))


if __name__ == '__main__':
    unittest.main()
