import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('project_capsule', ROOT / 'tools/project_capsule.py')
CAPSULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CAPSULE)


class ProjectCapsuleTests(unittest.TestCase):
    def test_schema_rejects_missing_and_unknown_fields(self):
        schema = json.loads((ROOT / 'docs/project-capsule/schema-v1.json').read_text())
        errors = CAPSULE.schema_errors(
            {'schema_version': 'c2.project-capsule.v1', 'unexpected': True}, schema, schema
        )
        self.assertTrue(any('identity: required' in error for error in errors))
        self.assertTrue(any('unexpected: unknown' in error for error in errors))

    def test_fast_is_read_only_and_checks_the_declared_changed_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            db = Path(temp_dir) / 'megavault.sqlite'
            manifest = yaml.safe_load((ROOT / 'project-capsule.yaml').read_text())
            canonical_workdir = manifest['repository']['canonical_workdir']
            with sqlite3.connect(db) as conn:
                conn.executescript('''
                    CREATE TABLE projects(project_id INTEGER, slug TEXT);
                    CREATE TABLE repositories(project_id INTEGER, repository_id TEXT, worktree_path TEXT);
                ''')
                conn.execute(
                    'INSERT INTO projects VALUES(?, ?)',
                    (manifest['identity']['project_id'], manifest['identity']['slug']),
                )
                conn.execute(
                    'INSERT INTO repositories VALUES(?, ?, ?)',
                    (manifest['identity']['project_id'], manifest['identity']['repository_id'], canonical_workdir),
                )
            report = CAPSULE.check(ROOT, 'FAST', db, ['project-capsule.yaml'], 5)
            self.assertEqual('PASS', report['status'], report)
            self.assertFalse(any(check['check'].startswith('hook:') for check in report['checks']))
            missing = CAPSULE.check(ROOT, 'FAST', db, ['unmapped.file'], 5)
            self.assertEqual('FAIL', missing['status'], missing)
            self.assertTrue(any(check['check'] == 'change_verification:unmapped.file' for check in missing['checks']))


if __name__ == '__main__':
    unittest.main()
