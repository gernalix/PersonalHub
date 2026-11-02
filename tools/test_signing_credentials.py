import json
import subprocess
import unittest
from unittest.mock import patch
import read_signing_credentials as signing
import deliver_personalhub_apk as delivery


class CredentialBoundaryTest(unittest.TestCase):
    def test_signing_vault_failure_has_no_local_fallback_or_secret_diagnostic(self):
        with patch.object(signing.subprocess, "run", return_value=subprocess.CompletedProcess([], 1, "secret", "secret")) as call:
            with self.assertRaisesRegex(RuntimeError, "^signing_vault_unavailable$"):
                signing.read_signing()
        command = call.call_args.args[0]
        self.assertIn("FEDORA_ANDROID_SIGNING_ENV", command)
        self.assertNotIn("secret", str(command))
        self.assertEqual(command.count("--var"), 4)

    def test_signing_rejects_partial_payload(self):
        with patch.object(signing.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "{}", "")):
            with self.assertRaisesRegex(RuntimeError, "signing_fields_incomplete"):
                signing.read_signing()

    def test_delivery_retrieves_fresh_token_and_discards_inherited_auth(self):
        with patch.dict(delivery.os.environ, {"GH_TOKEN": "stale", "GITHUB_TOKEN": "stale", "BWS_ACCESS_TOKEN": "bootstrap", "GH_DEBUG": "api"}):
            command, environment = delivery._command(["gh", "repo", "view"])
        for name in ("GH_TOKEN", "GITHUB_TOKEN", "BWS_ACCESS_TOKEN", "GH_DEBUG"):
            self.assertNotIn(name, environment)
        self.assertIn("GH_TOKEN=GITHUB_TOKEN", command)
        self.assertEqual(command[-3:], ["/usr/bin/gh", "repo", "view"])
        self.assertNotIn("stale", str(command))

    def test_git_revision_does_not_acquire_application_token(self):
        command, _ = delivery._command(["git", "rev-parse", "HEAD"])
        self.assertEqual(command, ["git", "rev-parse", "HEAD"])


if __name__ == "__main__":
    unittest.main()
