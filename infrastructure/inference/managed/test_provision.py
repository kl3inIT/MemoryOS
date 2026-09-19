"""Process interruption and overlapping publishers must preserve asset publication."""

import hashlib
import json
import multiprocessing
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import provision as provisioning
from runtime import RuntimeFailure, deadline, verify_assets


def write_asset(files, destination):
    destination.write_bytes(files[destination.name])
    destination.chmod(0o444)


def publish_after_release(manifest, root, files, started, release):
    def download(url, destination, item):
        started.set()
        if not release.wait(15):
            raise TimeoutError("Test publisher was not released")
        write_asset(files, destination)

    with patch.object(provisioning, "download", side_effect=download), \
            patch.object(provisioning, "free_space"):
        provisioning.provision(manifest, root)


@unittest.skipUnless(os.name == "posix", "Managed publication requires POSIX process locks")
class ProvisioningLifecycleTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / "assets"
        self.root.mkdir(mode=0o755)
        self.manifest = json.loads(Path(__file__).with_name("manifest.json").read_text(encoding="utf-8"))
        self.files = {item["path"]: ("fixture:" + item["path"]).encode()
                      for item in self.manifest["model"]["files"]}
        template = "<s>{{ message }}</s>"
        self.files["tokenizer_config.json"] = json.dumps({"chat_template": template}).encode()
        for item in self.manifest["model"]["files"]:
            data = self.files[item["path"]]
            item.update(sizeBytes=len(data), sha256=hashlib.sha256(data).hexdigest())
        self.manifest["model"]["chatTemplate"].update(
            sizeBytes=len(template), sha256=hashlib.sha256(template.encode()).hexdigest())
        self.manifest["model"]["totalAssetBytesIncludingTemplate"] = \
            sum(len(data) for data in self.files.values()) + len(template)

    def publisher(self):
        context = multiprocessing.get_context("fork")
        started, release = context.Event(), context.Event()
        child = context.Process(target=publish_after_release,
                                args=(self.manifest, self.root, self.files, started, release))
        child.start()

        def stop():
            if child.is_alive():
                child.kill()
            child.join(timeout=5)
            child.close()

        self.addCleanup(stop)
        self.assertTrue(started.wait(5), "Publisher did not reach its download")
        return child, release

    def publish(self):
        with patch.object(provisioning, "download", side_effect=lambda url, path, item: write_asset(self.files, path)), \
                patch.object(provisioning, "free_space"):
            return provisioning.provision(self.manifest, self.root)

    def assert_published(self):
        directory = verify_assets(self.manifest, self.root)
        self.assertEqual(self.files["model.safetensors"], (directory / "model.safetensors").read_bytes())

    def test_killed_publisher_does_not_require_deleting_abandoned_or_previous_assets(self):
        retained = self.root / ".provision-retained"
        retained.mkdir()
        (retained / "partial").write_bytes(b"operator-owned recovery material")
        previous = self.root / "previous-revision"
        previous.mkdir()
        (previous / "model.safetensors").write_bytes(b"previous accepted model")
        child, _ = self.publisher()
        child.kill()
        child.join(timeout=5)
        self.assertFalse(child.is_alive())

        self.assertEqual("ASSETS_PUBLISHED_AND_VERIFIED", self.publish())
        self.assert_published()
        self.assertEqual(b"operator-owned recovery material", (retained / "partial").read_bytes())
        self.assertEqual(b"previous accepted model", (previous / "model.safetensors").read_bytes())

    def test_live_publisher_rejects_overlap_without_waiting_and_keeps_its_publication(self):
        child, release = self.publisher()
        with deadline(2), self.assertRaisesRegex(RuntimeFailure, "^ASSET_PROVISIONING_BUSY$"):
            self.publish()
        release.set()
        child.join(timeout=5)
        self.assertEqual(0, child.exitcode)
        self.assert_published()
        with patch.object(provisioning, "download", side_effect=AssertionError("Warm assets must remain offline")):
            self.assertEqual("ASSETS_ALREADY_VERIFIED", provisioning.provision(self.manifest, self.root))


if __name__ == "__main__":
    unittest.main()
