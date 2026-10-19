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


if __name__ == '__main__':
    unittest.main()
