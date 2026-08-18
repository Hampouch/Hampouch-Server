#!/usr/bin/env python3

import json
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


class DatadogMonitoringContractTest(unittest.TestCase):
    def test_github_workflow_runs_at_ten_kst_and_uses_only_deployed_files(self):
        workflow = (
            ROOT / ".github/workflows/datadog-alert-path.yml"
        ).read_text(encoding="utf-8")

        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("schedule:", workflow)
        self.assertIn('cron: "0 1 * * *"', workflow)
        self.assertNotIn("actions/checkout", workflow)
        self.assertNotIn("scp ", workflow)
        self.assertIn("datadog_verification.py alert-path", workflow)

    def test_deployment_uses_container_uptime_and_keeps_restart_monitor_contract(
        self,
    ):
        config = json.loads(
            (
                ROOT / "scripts/deployment/datadog-verification.json"
            ).read_text(encoding="utf-8")
        )

        metrics_by_name = {
            metric["name"]: metric["query"]
            for metric in config["metrics"]
        }

        restart_monitor = next(
            monitor
            for monitor in config["monitors"]
            if monitor["id_env"] == "DD_MONITOR_CONTAINER_RESTART_ID"
        )

        self.assertEqual(
            "max:container.uptime{env:prod,container_name:hampouch-server}",
            metrics_by_name["app-container-uptime"],
        )

        self.assertNotIn(
            "container-restarts",
            metrics_by_name,
        )

        self.assertEqual(
            "change(avg(last_5m),last_5m):"
            "max:container.restarts{env:prod} "
            "by {container_name} > 0",
            restart_monitor["query"],
        )

        self.assertEqual(
            "show_and_notify_no_data",
            restart_monitor["options"]["on_missing_data"],
        )

        self.assertNotIn(
            "notify_no_data",
            restart_monitor["options"],
        )

    def test_deployment_refreshes_an_agent_that_supports_restart_metrics(self):
        compose = (
            ROOT / "docker-compose.prod.yml"
        ).read_text(encoding="utf-8")

        deployment = (
            ROOT / "scripts/deployment/deploy-and-verify.sh"
        ).read_text(encoding="utf-8")

        verification = (
            ROOT / "scripts/observability/verify-datadog.sh"
        ).read_text(encoding="utf-8")

        self.assertIn(
            "registry.datadoghq.com/agent:7.82.1",
            compose,
        )

        self.assertIn(
            '"${compose[@]}" pull datadog',
            deployment,
        )

        self.assertIn(
            "MIN_DATADOG_AGENT_VERSION:-7.82.1",
            verification,
        )


if __name__ == "__main__":
    unittest.main()