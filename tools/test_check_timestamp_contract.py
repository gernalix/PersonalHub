import unittest

from check_timestamp_contract import is_instant


class TimestampContractTest(unittest.TestCase):
    def test_instants(self):
        for column in ("createdAt", "updated_at", "timestamp_utc", "scheduled_for_utc", "reminderAt", "last_fired_at"):
            with self.subTest(column=column):
                self.assertTrue(is_instant(column))

    def test_non_instants(self):
        for column in ("lat", "longitude", "duration_ms", "median_reaction_time_ms",
                       "mean_inter_key_interval_ms", "prescription_date_utc", "startDate",
                       "epoch_day", "cooldown_ms", "created_at_offset_ms"):
            with self.subTest(column=column):
                self.assertFalse(is_instant(column))


if __name__ == "__main__":
    unittest.main()
