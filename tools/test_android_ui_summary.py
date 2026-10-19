from __future__ import annotations

import subprocess
import unittest
from unittest import mock

import android_ui_summary as ui


class AndroidUiFreshnessTests(unittest.TestCase):
    def test_zero_exit_dump_error_never_reads_previous_xml(self):
        def fake(serial, *args):
            if args[:3] == ('shell', 'uiautomator', 'dump'):
                return subprocess.CompletedProcess(args, 0, '', 'ERROR: could not get idle state')
            if args[:3] == ('shell', 'rm', '-f'):
                return subprocess.CompletedProcess(args, 0, '', '')
            self.fail('A failed dump must never read XML')
        with mock.patch.object(ui, '_adb', side_effect=fake) as adb:
            with self.assertRaisesRegex(RuntimeError, 'idle state'):
                ui._dump_xml('pixel')
        self.assertEqual(2, adb.call_count)

    def test_each_success_reads_and_cleans_only_its_new_file(self):
        paths = []
        xml = '<?xml version="1.0"?><hierarchy><node text="Places" /></hierarchy>'
        def fake(serial, *args):
            self.assertEqual('pixel', serial)
            if args[:3] == ('shell', 'uiautomator', 'dump'):
                paths.append(args[3])
                return subprocess.CompletedProcess(args, 0, 'UI hierchary dumped to: ' + args[3], '')
            self.assertEqual(paths[-1], args[-1])
            return subprocess.CompletedProcess(args, 0, xml if args[:2] == ('exec-out', 'cat') else '', '')
        with mock.patch.object(ui, '_adb', side_effect=fake) as adb:
            self.assertEqual(xml, ui._dump_xml('pixel'))
            self.assertEqual(xml, ui._dump_xml('pixel'))
        self.assertNotEqual(paths[0], paths[1])
        self.assertEqual(6, adb.call_count)

    def test_missing_fresh_file_is_refused_and_cleanup_is_attempted(self):
        def fake(serial, *args):
            if args[:3] == ('shell', 'uiautomator', 'dump'):
                return subprocess.CompletedProcess(args, 0, 'UI hierchary dumped to: ' + args[3], '')
            if args[:2] == ('exec-out', 'cat'):
                return subprocess.CompletedProcess(args, 1, '', 'No such file')
            return subprocess.CompletedProcess(args, 0, '', '')
        with mock.patch.object(ui, '_adb', side_effect=fake) as adb:
            with self.assertRaisesRegex(RuntimeError, 'No such file'):
                ui._dump_xml('pixel')
        self.assertEqual(('shell', 'rm', '-f'), adb.call_args.args[1:4])

class AdbInputIsolationTests(unittest.TestCase):
    """Real subprocesses must not consume commands queued for their parent UI controller."""

    def assert_parent_commands_survive(self, mode, explicit_payload=False):
        import json
        import os
        from pathlib import Path
        import sys
        import tempfile

        with tempfile.TemporaryDirectory(prefix='ph-adb-stdin-') as directory:
            fake = Path(directory) / 'adb'
            fake.write_text('#!' + sys.executable + '\nimport sys\nsys.stdout.write(sys.stdin.read())\n')
            fake.chmod(0o700)
            code = f'''
import json,sys
sys.path.insert(0,{str(Path(__file__).resolve().parent)!r})
mode=sys.argv[1]
if mode=='ui':
 import android_ui_summary as module
 output=module._adb('host-fixture','shell','true').stdout
elif mode=='preflight':
 import android_target_preflight as module
 output=module.run_adb(['-s','host-fixture','shell','true']).stdout
else:
 from android_paired_cutover_v24 import Pixel
 device=Pixel.__new__(Pixel)
 device.adb=sys.argv[2]
 device.serial='host-fixture'
 output=device.run('shell','true',input_bytes=b'fixture payload' if mode=='payload' else None).decode()
print(json.dumps({{'child':output,'parent':sys.stdin.read()}}))
'''
            env = dict(os.environ, PATH=directory + os.pathsep + os.environ.get('PATH', ''))
            result = subprocess.run(
                [sys.executable, '-c', code, mode, str(fake)], input='queued controller command\n',
                text=True, capture_output=True, check=True, timeout=15, env=env,
            )
            self.assertEqual(
                {'child': 'fixture payload' if explicit_payload else '', 'parent': 'queued controller command\n'},
                json.loads(result.stdout),
            )

    def test_ui_reader_preserves_parent_control_input(self):
        self.assert_parent_commands_survive('ui')

    def test_target_preflight_preserves_parent_control_input(self):
        self.assert_parent_commands_survive('preflight')

    def test_pixel_commands_preserve_parent_control_input(self):
        self.assert_parent_commands_survive('pixel')

    def test_pixel_explicit_payload_is_sent_without_consuming_parent_input(self):
        self.assert_parent_commands_survive('payload', explicit_payload=True)


if __name__ == '__main__':
    unittest.main()
