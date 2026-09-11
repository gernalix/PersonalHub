from __future__ import annotations

import unittest

import android_target_preflight as preflight


class AndroidTargetPreflightTests(unittest.TestCase):
    def test_classifies_pixel_tcl_and_emulator_without_transport_id(self) -> None:
        rows = preflight.parse_devices(
            "List of devices attached\n"
            "P device product:akita model:Pixel_8a device:akita transport_id:7\n"
            "T device product:tcl model:TCL_6102H device:6102H transport_id:8\n"
            "emulator-5554 device product:sdk model:sdk_gphone64_x86_64 transport_id:9\n"
        )
        self.assertEqual(["pixel", "tcl", "emulator"], [row["kind"] for row in rows])
        self.assertTrue(all("transport_id" not in row for row in rows))

    def test_offline_target_is_not_eligible(self) -> None:
        rows = preflight.parse_devices(
            "List of devices attached\nP offline product:akita model:Pixel_8a device:akita\n"
        )
        self.assertEqual("offline", rows[0]["state"])


if __name__ == "__main__":
    unittest.main()
