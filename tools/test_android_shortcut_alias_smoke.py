import unittest

from android_shortcut_alias_smoke import launched_activity


class LaunchedActivityTest(unittest.TestCase):
    def test_android_abbreviated_activity_name(self):
        output = "Activity: com.gernalix.personalhub/.salute.SaluteActivity\n"
        self.assertEqual(
            "com.gernalix.personalhub.salute.SaluteActivity",
            launched_activity(output, "com.gernalix.personalhub"),
        )

    def test_fully_qualified_feature_activity_name(self):
        output = "Activity: com.gernalix.personalhub/com.example.multitimetracker.MainActivity\n"
        self.assertEqual(
            "com.example.multitimetracker.MainActivity",
            launched_activity(output, "com.gernalix.personalhub"),
        )


if __name__ == "__main__":
    unittest.main()
