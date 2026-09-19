import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


@unittest.skipUnless(os.name == "posix" and all(shutil.which(tool) for tool in ("bash", "awk", "jq")),
                     "Deployment operations require POSIX Bash, awk and jq")
class InferenceAdmissionOperationsTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for name in ("transaction", "state", "control"):
            (self.root / name).mkdir()
        self.tcp_header = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt uid timeout inode\n"
        self.listener = "0: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 1654 0 1\n"
        (self.root / "tcp").write_text(self.tcp_header + self.listener)
        (self.root / "tcp6").write_text(self.tcp_header)
        self.environment = {
            **os.environ,
            "root": str(self.root), "tx": str(self.root / "transaction"),
            "state": str(self.root / "state"), "release": "a" * 40 + "-1-1",
            "CONTROL": str(self.root / "control"),
            "OPS_SOURCE": str(Path(__file__).with_name("inference-operations.sh").resolve()),
        }

    def operate(self, body):
        return subprocess.run(["bash", "-c", '''set -Eeuo pipefail
source "$OPS_SOURCE"
serving_target=candidate
inference_paths() { serving_control=$CONTROL; }
# Replace only Docker IO; run the production occupancy parser against TCP fixtures.
inference_compose() {
  if [[ "$1" == ps ]]; then printf '%s\\n' "${@: -1}"; return; fi
  shift 5
  awk "$1" "$root/tcp" "$root/tcp6"
}
docker() { printf '%s\\n' '[{"State":{"Running":true,"Restarting":false}}]'; }
timeout() { shift; "$@"; }
''' + body], env=self.environment, capture_output=True, text=True, timeout=5)

    def test_admission_is_closed_before_waiting_for_engine_idle(self):
        result = self.operate('''
inference_probe() { [[ "$1" == idle && -f "$CONTROL/maintenance" ]]; }
inference_drain
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "transaction/inference.drained").is_file())
        gate = self.root / "control/maintenance"
        self.assertEqual(stat.S_IMODE(gate.stat().st_mode), 0o644)

    def test_drain_expiry_keeps_admission_closed_without_claiming_settlement(self):
        result = self.operate('''
inference_probe() { return 1; }
sleep() { SECONDS=$((SECONDS + 200)); }
inference_drain
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((self.root / "control/maintenance").is_file())
        self.assertFalse((self.root / "transaction/inference.drained").exists())

    def test_partial_body_blocks_drain_even_when_engine_is_idle_then_recovers(self):
        # This is the socket held by a POST admitted before maintenance while its
        # body is still uploading. There need not yet be any native running work.
        (self.root / "tcp").write_text(
            self.tcp_header + self.listener
            + "1: 0200000A:1F90 0300000A:C001 01 00000000:00000001 00:00000000 00000000 1654 0 2\n"
        )
        (self.root / "state/pending").write_text("reserved")
        result = self.operate('''
inference_probe() { touch "$tx/native-idle-observed"; return 0; }
sleep() { SECONDS=$((SECONDS + 200)); }
inference_drain
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "transaction/native-idle-observed").exists())
        self.assertFalse((self.root / "transaction/inference.drained").exists())
        self.assertTrue((self.root / "control/maintenance").is_file())
        self.assertEqual((self.root / "state/pending").read_text(), "reserved")

        # Explicit client settlement permits a retry; native quiet remains required.
        (self.root / "tcp").write_text(self.tcp_header + self.listener)
        result = self.operate('''
inference_probe() { [[ "$1" == idle && -f "$CONTROL/maintenance" ]]; }
inference_drain
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "transaction/inference.drained").is_file())
        self.assertTrue((self.root / "control/maintenance").is_file())
        self.assertEqual((self.root / "state/pending").read_text(), "reserved")

    def test_disconnected_client_with_live_ipv6_upstream_still_blocks_drain(self):
        (self.root / "tcp6").write_text(
            self.tcp_header
            + "0: 00000000000000000000000001000000:C002 00000000000000000000000002000000:1F40 08 "
            + "00000000:00000000 00:00000000 00000000 1654 0 3\n"
        )
        result = self.operate('''
inference_probe() { return 0; }
sleep() { SECONDS=$((SECONDS + 200)); }
inference_drain
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "transaction/inference.drained").exists())
        self.assertTrue((self.root / "control/maintenance").is_file())

    def test_unavailable_socket_evidence_fails_closed(self):
        (self.root / "tcp6").unlink()
        result = self.operate('''
inference_probe() { return 0; }
sleep() { SECONDS=$((SECONDS + 200)); }
inference_drain
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "transaction/inference.drained").exists())
        self.assertTrue((self.root / "control/maintenance").is_file())

    def test_closed_time_wait_socket_does_not_prevent_settlement(self):
        (self.root / "tcp").write_text(
            self.tcp_header + self.listener
            + "1: 0200000A:1F90 0300000A:C001 06 00000000:00000000 03:00000001 00000000 0 0 0\n"
        )
        result = self.operate('''
inference_probe() { [[ "$1" == idle && -f "$CONTROL/maintenance" ]]; }
inference_drain
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "transaction/inference.drained").is_file())

    def test_stopped_engine_recovery_requires_continuously_closed_admission(self):
        (self.root / "transaction/inference.drained").touch()
        (self.root / "control/maintenance").touch()
        result = self.operate('''
docker() { printf '%s\\n' '[{"State":{"Running":false,"Restarting":false}}]'; }
inference_probe() { return 1; }
inference_drain
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "transaction/inference.drained").is_file())

        # A receipt from before an admission reopen cannot certify the stopped engine.
        (self.root / "control/maintenance").unlink()
        result = self.operate('''
docker() { printf '%s\\n' '[{"State":{"Running":false,"Restarting":false}}]'; }
inference_probe() { return 1; }
sleep() { SECONDS=$((SECONDS + 200)); }
inference_drain
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root / "transaction/inference.drained").exists())
        self.assertTrue((self.root / "control/maintenance").is_file())
    def test_failed_recheck_retains_prior_closed_drain_for_stopped_recovery(self):
        (self.root / "control/maintenance").touch()
        receipt = self.root / "transaction/inference.drained"
        receipt.write_text("earlier-proof")
        result = self.operate('''
docker() { return 1; }
sleep() { SECONDS=$((SECONDS + 200)); }
inference_drain
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(receipt.read_text(), "earlier-proof")
        self.assertTrue((self.root / "control/maintenance").is_file())
        result = self.operate('''
docker() { printf '%s\\n' '[{"State":{"Running":false,"Restarting":false}}]'; }
inference_probe() { return 1; }
inference_drain
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / "control/maintenance").is_file())


    def test_resume_invalidates_settlement_before_admitting_generation(self):
        (self.root / "control/maintenance").touch()
        (self.root / "transaction/inference.drained").touch()
        result = self.operate('''
inference_probe() {
  if [[ "$1" == ready ]]; then return 0; fi
  [[ ! -e "$tx/inference.drained" && ! -e "$CONTROL/maintenance" ]]
}
inference_resume
''')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.root / "transaction/inference.drained").exists())
        self.assertFalse((self.root / "control/maintenance").exists())

    def test_failed_generation_recloses_admission_for_recovery(self):
        (self.root / "control/maintenance").touch()
        (self.root / "transaction/inference.drained").touch()
        result = self.operate('''
inference_probe() { [[ "$1" == ready ]]; }
inference_resume
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((self.root / "control/maintenance").is_file())
        self.assertFalse((self.root / "transaction/inference.drained").exists())

    def test_ready_failure_does_not_reopen_admission(self):
        (self.root / "control/maintenance").touch()
        result = self.operate('''
inference_probe() { return 1; }
inference_resume
''')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((self.root / "control/maintenance").is_file())


@unittest.skipUnless(os.name == "posix" and all(shutil.which(tool) for tool in ("bash", "jq")),
                     "Serving recovery requires POSIX Bash and jq")
class InferenceRecoveryNetworkTest(unittest.TestCase):
    def test_serving_only_restore_rejects_network_drift_without_changing_retained_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state, transaction = root / "state", root / "transaction"
            state.mkdir()
            transaction.mkdir()
            source = Path(__file__).resolve().parents[2]
            profile = "smollm2-135m-12fd25f-v1"
            (state / "current.inference.source").write_text(str(source))
            (transaction / "previous.inference.source").write_text(str(source))
            (state / "current.inference.receipt.json").write_text(json.dumps(
                {"keyFile": str(root / "current-key"), "credentialVersion": "current"}))
            networks = lambda suffix: {
                "memoryos-inference-client": {"name": "client-" + suffix},
                "memoryos-inference-backend": {"name": "backend-" + suffix},
            }
            (state / "current.inference.json").write_text(json.dumps({"networks": networks("current")}))
            previous = {
                "networks": networks("previous"),
                "services": {
                    "inference-gateway": {"volumes": [
                        {"target": "/var/lib/memoryos-inference/control", "source": str(root / "control")}]},
                    "vllm": {"volumes": [
                        {"target": "/run/secrets/inference_api_key", "source": str(root / "old-key")},
                        {"target": "/var/lib/memoryos-inference/assets", "source": str(root / "assets")},
                        {"target": "/var/cache/memoryos-inference", "source": str(root / "cache")}]},
                },
            }
            previous_json = transaction / "previous.inference.json"
            previous_json.write_text(json.dumps(previous))
            previous_env = transaction / "previous.inference.env"
            previous_env.write_text("MEMORYOS_INFERENCE_CLIENT_NETWORK=client-previous\n")
            (transaction / "previous.inference.receipt.json").write_text(json.dumps({"manifestSha256": "f" * 64}))
            (transaction / "previous.inference.operator.json").write_text(json.dumps({"credentialVersion": "previous"}))
            before = previous_env.read_bytes(), previous_json.read_bytes()
            result = subprocess.run(["bash", "-c", '''
set -Eeuo pipefail
source "$OPS_SOURCE"
docker() { printf '%s\\n' "$API_IDENTITY"; }
inference_verify_artifacts() { :; }
inference_verify_images() { :; }
python3() { :; }
inference_compose() { cat "$tx/previous.inference.json"; }
serving_target=previous
inference_compatible_restore
'''], env={**os.environ, "root": str(root), "tx": str(transaction), "state": str(state),
           "release": "a" * 40 + "-1-1",
           "OPS_SOURCE": str(Path(__file__).with_name("inference-operations.sh").resolve()),
           "API_IDENTITY": json.dumps([{"Config": {"Labels": {"io.memoryos.chat.tokenizer-profiles": profile}}}])},
                capture_output=True, text=True, timeout=5)
            self.assertNotEqual(0, result.returncode, "Incompatible private networks must not be restored")
            self.assertEqual(before, (previous_env.read_bytes(), previous_json.read_bytes()))


if __name__ == "__main__":
    unittest.main()
