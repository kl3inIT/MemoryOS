import copy
import json
from pathlib import Path

from memoryos_bench.provision import fingerprint, source_of

CORPUS = Path(__file__).resolve().parent.parent / "corpus"
MANIFEST = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
LAYOUT = json.loads((CORPUS / "layout.json").read_text(encoding="utf-8"))


def test_every_document_has_a_source_and_a_unique_key_and_digest() -> None:
    keys = [d["key"] for d in MANIFEST["documents"]]
    digests = [d["sha256"] for d in MANIFEST["documents"]]
    assert len(set(keys)) == len(keys)
    # One file per digest: a Source deduplicates by content, so a repeated digest would be lost.
    assert len(set(digests)) == len(digests)
    for document in MANIFEST["documents"]:
        assert source_of(document) in LAYOUT["sources"], document["key"]
        assert document["url"].startswith("https://"), document["key"]


def test_every_source_and_role_names_known_groups() -> None:
    for source in LAYOUT["sources"].values():
        assert set(source["groups"]) <= set(LAYOUT["groups"])
    for groups in LAYOUT["roles"].values():
        assert set(groups) <= set(LAYOUT["groups"])


def test_the_fingerprint_changes_with_a_document_or_an_access_change() -> None:
    base = fingerprint(MANIFEST, LAYOUT)
    assert fingerprint(copy.deepcopy(MANIFEST), copy.deepcopy(LAYOUT)) == base

    changed_file = copy.deepcopy(MANIFEST)
    changed_file["documents"][0]["sha256"] = "0" * 64
    assert fingerprint(changed_file, LAYOUT) != base

    changed_access = copy.deepcopy(LAYOUT)
    changed_access["roles"]["finance"] = ["finance", "legal"]
    assert fingerprint(MANIFEST, changed_access) != base


def test_the_fingerprint_ignores_descriptive_fields() -> None:
    relabelled = copy.deepcopy(MANIFEST)
    relabelled["documents"][0]["kind"] = "renamed"
    relabelled["note"] = "edited"
    assert fingerprint(relabelled, LAYOUT) == fingerprint(MANIFEST, LAYOUT)
