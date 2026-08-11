#!/usr/bin/env python3

import importlib.util
import os
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


MODULE_PATH = Path(__file__).with_name("datadog_verification.py")
SPEC = importlib.util.spec_from_file_location("datadog_verification", MODULE_PATH)
verification = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verification)


class FakeClient:
    def __init__(self, monitor):
        self.monitor = monitor
        self.submitted_statuses = []
        self.metric_response = {"series": []}
        self.log_response = {"data": []}
        self.downtime_response = {"data": []}

    def request(self, method, path, params=None, body=None, require_app_key=True):
        if path == "/api/v1/validate":
            return {"valid": True}
        raise AssertionError(f"unexpected request: {method} {path}")

    def get(self, path, params=None):
        if path == "/api/v1/query":
            return self.metric_response
        if path.startswith("/api/v2/monitor/"):
            return self.downtime_response
        if path.startswith("/api/v1/monitor/"):
            result = dict(self.monitor)
            if self.submitted_statuses:
                status = self.submitted_statuses[-1]
                signal_id = self.last_signal_id
                now_epoch = int(time.time())
                if status == 2:
                    group = {"status": "Alert", "last_triggered_ts": now_epoch}
                else:
                    group = {"status": "OK", "last_resolved_ts": now_epoch}
                result["state"] = {"groups": {f"signal_id:{signal_id}": group}}
            return result
        raise AssertionError(f"unexpected GET: {path}")

    def post(self, path, body, require_app_key=True):
        if path == "/api/v2/logs/events/search":
            return self.log_response
        if path == "/api/v1/check_run":
            check = body[0]
            self.submitted_statuses.append(check["status"])
            self.last_signal_id = next(tag.split(":", 1)[1] for tag in check["tags"] if tag.startswith("signal_id:"))
            return {"status": "ok"}
        raise AssertionError(f"unexpected POST: {path}")


class FakeDiscordClient:
    def __init__(self, received=True):
        self.received_result = received
        self.queries = []

    def received(self, signal_id, phase):
        self.queries.append((signal_id, phase))
        return self.received_result


def alert_monitor():
    return {
        "id_env": "DD_ALERT_TEST_MONITOR_ID",
        "name": "[Hampouch] Alert path verification",
        "type": "service check",
        "query": '"hampouch.alert_path".over("env:prod").by("signal_id").last(1).count_by_status()',
        "tags": ["env:prod", "managed-by:hampouch", "purpose:alert-path-test"],
        "options": {
            "notify_no_data": False,
            "thresholds": {"critical": 1, "ok": 1},
        },
        "message_fragments": [
            "HAMPOUCH_ALERT_TEST",
            "phase:alert",
            "phase:recovery",
            "signal_id:{{signal_id.name}}",
            "{{#is_alert}}",
            "{{#is_recovery}}",
        ],
    }


def actual_monitor():
    expected = alert_monitor()
    return {
        "draft_status": "published",
        "matching_downtimes": [],
        "name": expected["name"],
        "type": expected["type"],
        "query": expected["query"],
        "tags": expected["tags"],
        "options": expected["options"],
        "message": "{{#is_alert}}HAMPOUCH_ALERT_TEST phase:alert "
        "signal_id:{{signal_id.name}} @webhook-hampouch-discord{{/is_alert}} "
        "{{#is_recovery}}HAMPOUCH_ALERT_TEST phase:recovery "
        "signal_id:{{signal_id.name}} @webhook-hampouch-discord{{/is_recovery}}",
    }


class DatadogVerificationTest(unittest.TestCase):
    def setUp(self):
        self.original_environment = os.environ.copy()
        os.environ["DD_REQUIRED_NOTIFICATION_HANDLES"] = "@webhook-hampouch-discord"
        os.environ["DD_ALERT_TEST_MONITOR_ID"] = "123"

    def tearDown(self):
        os.environ.clear()
        os.environ.update(self.original_environment)

    def test_metric_requires_a_point_after_deployment(self):
        from_epoch = 1_700_000_000
        stale = {"series": [{"pointlist": [[(from_epoch - 1) * 1000, 1.0]]}]}
        fresh = {"series": [{"pointlist": [[from_epoch * 1000, 0.0]]}]}

        self.assertFalse(verification.metric_has_fresh_point(stale, from_epoch))
        self.assertTrue(verification.metric_has_fresh_point(fresh, from_epoch))

    def test_monitor_rejects_active_downtime(self):
        monitor = actual_monitor()
        client = FakeClient(monitor)
        client.downtime_response = {"data": [{"id": "downtime"}]}

        with self.assertRaisesRegex(verification.VerificationError, "활성 downtime"):
            verification.verify_monitor(client, alert_monitor(), ["@webhook-hampouch-discord"])

    def test_monitor_accepts_omitted_false_default_option(self):
        monitor = actual_monitor()
        monitor["options"].pop("notify_no_data")

        verification.verify_monitor(
            FakeClient(monitor),
            alert_monitor(),
            ["@webhook-hampouch-discord"],
        )

    def test_monitor_accepts_on_missing_data_option(self):
        expected = alert_monitor()
        expected["options"] = {
            "on_missing_data": "show_and_notify_no_data",
            "thresholds": {"critical": 1, "ok": 1},
        }
        monitor = actual_monitor()
        monitor["options"] = dict(expected["options"])

        verification.verify_monitor(
            FakeClient(monitor),
            expected,
            ["@webhook-hampouch-discord"],
        )

    def test_deployment_data_requires_metrics_and_logs(self):
        from_epoch = 1_700_000_000
        client = FakeClient(actual_monitor())
        client.metric_response = {"series": [{"pointlist": [[from_epoch * 1000, 1.0]]}]}
        client.log_response = {"data": [{"id": "log"}]}
        config = {
            "metrics": [{"name": "host", "query": "max:system.mem.pct_usable{env:prod}"}],
            "logs": [{"name": "app", "query": "service:hampouch-server"}],
        }

        pending = verification.pending_deployment_data(client, config, from_epoch, "abcdef12")

        self.assertEqual([], pending)

    def test_alert_path_confirms_alert_and_recovery(self):
        client = FakeClient(actual_monitor())
        discord_client = FakeDiscordClient()
        config = {"monitors": [alert_monitor()]}
        args = SimpleNamespace(
            respect_interval=False,
            signal_id="test-signal-1234",
            attempts=1,
            interval_seconds=0,
        )
        original_cwd = Path.cwd()
        with tempfile.TemporaryDirectory() as temp_dir:
            try:
                os.chdir(temp_dir)
                verification.run_alert_path(client, config, args, discord_client)
                self.assertTrue(Path(".datadog-alert-test-last-success").is_file())
            finally:
                os.chdir(original_cwd)

        self.assertEqual([2, 0], client.submitted_statuses)
        self.assertEqual(
            [("test-signal-1234", "alert"), ("test-signal-1234", "recovery")],
            discord_client.queries,
        )

    def test_alert_path_sends_recovery_when_discord_delivery_fails(self):
        client = FakeClient(actual_monitor())
        discord_client = FakeDiscordClient(received=False)
        config = {"monitors": [alert_monitor()]}
        args = SimpleNamespace(
            respect_interval=False,
            signal_id="test-signal-5678",
            attempts=1,
            interval_seconds=0,
        )

        with self.assertRaises(verification.VerificationError):
            verification.run_alert_path(client, config, args, discord_client)

        self.assertEqual([2, 0], client.submitted_statuses)

    def test_discord_client_reads_only_the_configured_channel(self):
        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self):
                return b'[{"webhook_id":"456","content":"message"}]'

        client = verification.DiscordEvidenceClient("secret-token", "123", "456")
        with patch.object(
            verification.urllib.request, "urlopen", return_value=Response()
        ) as urlopen:
            messages = client.messages()

        request = urlopen.call_args.args[0]
        self.assertEqual(
            "https://discord.com/api/v10/channels/123/messages?limit=100",
            request.full_url,
        )
        self.assertNotIn("secret-token", request.full_url)
        self.assertEqual("Bot secret-token", request.get_header("Authorization"))
        self.assertEqual("456", messages[0]["webhook_id"])

    def test_discord_delivery_requires_configured_webhook_and_exact_markers(self):
        message = {
            "webhook_id": "456",
            "content": "HAMPOUCH_ALERT_TEST phase:alert signal_id:test-signal-1234",
            "embeds": [],
        }

        self.assertTrue(
            verification.discord_message_has_delivery(
                message, "456", "test-signal-1234", "alert"
            )
        )
        self.assertFalse(
            verification.discord_message_has_delivery(
                message, "999", "test-signal-1234", "alert"
            )
        )
        self.assertFalse(
            verification.discord_message_has_delivery(
                message, "456", "test-signal-1234", "recovery"
            )
        )


if __name__ == "__main__":
    unittest.main()
