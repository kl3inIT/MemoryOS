import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error


spec = importlib.util.spec_from_file_location("inference_credential", Path(__file__).with_name("inference-credential.py"))
credential = importlib.util.module_from_spec(spec)
spec.loader.exec_module(credential)


class CatalogBoundary:
    def __init__(self, provider, mutate_after_write=False):
        self.provider = copy.deepcopy(provider)
        self.mutate_after_write = mutate_after_write
        self.written_credential = None
        self.put_count = 0

    def open(self, request, timeout):
        if request.method == "PUT":
            if not request.full_url.endswith("?revision=" + str(self.provider["revision"])):
                raise urllib.error.HTTPError(request.full_url, 409, "conflict", {}, None)
            if request.headers.get("X-memoryos-csrf") != "1":
                raise urllib.error.HTTPError(request.full_url, 403, "csrf", {}, None)
            body = json.loads(request.data)
            self.written_credential = body.pop("credential")
            self.provider.update(body)
            self.provider["credentialConfigured"] = True
            self.provider["revision"] += 1
            self.put_count += 1
            response = copy.deepcopy(self.provider)
            if self.mutate_after_write:
                self.provider["revision"] += 1
        else:
            response = [self.provider]
        return io.BytesIO(json.dumps(response).encode())


@unittest.skipUnless(os.name == "posix", "Deployment secret permissions require the POSIX host")
class InferenceCredentialTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.key = self.protected("key", "a" * 64 + "\n")
        self.cookie = self.protected("cookie", "SESSION=synthetic-session")
        self.provider = {
            "id": "7ad69e28-8233-4022-94f8-743268ad73ab", "name": "Managed",
            "adapterType": "openai", "baseUrl": "http://inference-gateway:8080/v1",
            "enabled": True, "isPublic": False, "groupIds": ["02b60413-e3c4-47c3-b897-fc91033ffca8"],
            "personaIds": ["044f103d-559b-46b7-b188-c871d15cb25b"],
            "credentialConfigured": True, "revision": 7,
        }
        self.request = self.protected("request.json", json.dumps({
            "applicationOrigin": "https://memoryos.invalid", "providerId": self.provider["id"],
            "providerRevision": 7, "sessionCookieFile": str(self.cookie),
            "expectedProviderBaseUrl": self.provider["baseUrl"],
        }))

    def protected(self, name, content):
        path = self.root / name
        path.write_text(content, encoding="utf-8")
        path.chmod(0o600)
        return path

    def test_rotation_preserves_access_and_emits_only_nonsecret_revision_receipt(self):
        boundary = CatalogBoundary(self.provider)
        with patch.object(credential.urllib.request, "build_opener", return_value=boundary):
            result = credential.handoff(self.request, self.key)
        self.assertEqual(boundary.provider["groupIds"], self.provider["groupIds"])
        self.assertEqual(boundary.provider["personaIds"], self.provider["personaIds"])
        self.assertFalse(boundary.provider["isPublic"])
        self.assertEqual(boundary.written_credential, {"action": "REPLACE", "value": "a" * 64})
        self.assertEqual(result, {"providerId": self.provider["id"], "previousRevision": 7,
                                  "providerRevision": 8, "credentialConfigured": True})
        self.assertNotIn("a" * 64, json.dumps(result))
        self.assertNotIn("synthetic-session", json.dumps(result))

    def test_stale_revision_prevents_any_credential_change(self):
        boundary = CatalogBoundary({**self.provider, "revision": 8})
        with patch.object(credential.urllib.request, "build_opener", return_value=boundary):
            with self.assertRaises(ValueError):
                credential.handoff(self.request, self.key)
        self.assertEqual(boundary.put_count, 0)
        self.assertIsNone(boundary.written_credential)

    def test_edit_during_handoff_prevents_success_receipt(self):
        boundary = CatalogBoundary(self.provider, mutate_after_write=True)
        with patch.object(credential.urllib.request, "build_opener", return_value=boundary):
            with self.assertRaises(ValueError):
                credential.handoff(self.request, self.key)
        self.assertEqual(boundary.put_count, 1)

    def test_resuming_a_handoff_refuses_a_later_provider_revision_without_retrying(self):
        receipt = self.protected("receipt.json", json.dumps({"providerId": self.provider["id"],
                                  "providerRevision": 8, "credentialConfigured": True}))
        boundary = CatalogBoundary({**self.provider, "revision": 9})
        with patch.object(credential.urllib.request, "build_opener", return_value=boundary):
            with self.assertRaises(ValueError):
                credential.handoff(self.request, verify_receipt=receipt)
        self.assertEqual(boundary.put_count, 0)

    def test_misdirected_provider_refuses_credential_disclosure(self):
        boundary = CatalogBoundary({**self.provider, "baseUrl": "https://unrelated.invalid/v1"})
        with patch.object(credential.urllib.request, "build_opener", return_value=boundary):
            with self.assertRaises(ValueError):
                credential.handoff(self.request, self.key)
        self.assertIsNone(boundary.written_credential)

    def test_shared_and_symlink_secret_inputs_are_rejected(self):
        self.key.chmod(0o640)
        with self.assertRaises(ValueError):
            credential.read_key(self.key)
        self.key.chmod(0o600)
        link = self.root / "key-link"
        link.symlink_to(self.key)
        with self.assertRaises(ValueError):
            credential.read_key(link)


if __name__ == "__main__":
    unittest.main()
