"""Regression boundary: pinned vLLM fingerprints only the trailing usage chunk."""

from email.message import Message
import io
import json
from pathlib import Path
import unittest
from unittest.mock import Mock

from probe import generation
from runtime import RuntimeFailure


class GenerationIdentityTest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads(Path(__file__).with_name("manifest.json").read_text(encoding="utf-8"))
        self.identity = "f" * 64
        self.model = self.manifest["model"]["servedModelName"]
        self.events = [
            {"choices": [{"index": 0, "delta": {"role": "assistant", "content": ""}, "finish_reason": None}]},
            {"choices": [{"index": 0, "delta": {"content": "Hello"}, "finish_reason": None}]},
            {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
            {"choices": [], "system_fingerprint": "memoryos-" + self.identity,
             "usage": {"prompt_tokens": 12, "completion_tokens": 3, "total_tokens": 15}},
        ]

    def client(self):
        body = b"".join(b"data: " + json.dumps({"model": self.model, **event}).encode() + b"\n\n"
                        for event in self.events) + b"data: [DONE]\n\n"
        response = io.BytesIO(body)
        response.headers = Message()
        response.headers["Content-Type"] = "text/event-stream"
        client = Mock()
        client.open.return_value = response
        client.get.return_value = json.dumps({"object": "list", "data": [{
            "id": self.model,
            "root": "/var/lib/memoryos-inference/assets/" + self.manifest["model"]["revision"],
            "max_model_len": self.manifest["provisionalRuntime"]["contextTokens"],
        }]}).encode()
        return client

    def test_terminal_usage_attests_valid_stream_without_intermediate_fingerprints(self):
        result = generation(self.client(), self.manifest, self.identity)
        self.assertEqual("stop", result["finishReason"])
        self.assertEqual(3, result["completionTokens"])

    def test_unattested_usage_does_not_accept_an_earlier_matching_fingerprint(self):
        self.events[0]["system_fingerprint"] = "memoryos-" + self.identity
        self.events[-1].pop("system_fingerprint")
        with self.assertRaisesRegex(RuntimeFailure, "GENERATION_IDENTITY_OR_ERROR"):
            generation(self.client(), self.manifest, self.identity)

    def test_terminal_identity_does_not_hide_conflicting_content_identity(self):
        self.events[1]["system_fingerprint"] = "memoryos-" + "e" * 64
        with self.assertRaisesRegex(RuntimeFailure, "GENERATION_IDENTITY_OR_ERROR"):
            generation(self.client(), self.manifest, self.identity)


if __name__ == "__main__":
    unittest.main()
