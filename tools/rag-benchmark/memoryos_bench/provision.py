"""Builds the benchmark corpus on MemoryOS from the manifest and the layout, idempotently.

A second run changes nothing. Anything the script did not create and cannot reconcile safely (an
unexpected file in a benchmark Source, a role that is also a member of another Group)
is reported and fails the run instead of being removed, unless `prune` is set.
"""

from __future__ import annotations

import hashlib
import json
import time
import urllib.request
from collections.abc import Iterator
from dataclasses import dataclass, field
from pathlib import Path

from memoryos_bench.api import Api, put_object

MEDIA_TYPES = {
    "pdf": "application/pdf",
    "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "doc": "application/msword",
}


@dataclass
class Report:
    created: list[str] = field(default_factory=list)
    problems: list[str] = field(default_factory=list)
    # Memberships --prune would remove, shown as one block read before --prune is allowed.
    would_remove: list[str] = field(default_factory=list)

    def note(self, line: str) -> None:
        self.created.append(line)
        print(line, flush=True)

    def problem(self, line: str) -> None:
        self.problems.append(line)
        print("PROBLEM " + line, flush=True)


def source_of(document: dict) -> str:
    return "noise" if document["use"] == "noise" else document["department"]


def fingerprint(manifest: dict, layout: dict) -> str:
    """Names the corpus a run was measured on: documents, where they live and who reads them."""
    canonical = {
        "documents": sorted(
            (d["id"], d["sha256"], layout["sources"][source_of(d)]["name"])
            for d in manifest["documents"]
        ),
        "sources": {
            s["name"]: sorted(layout["groups"][g] for g in s["groups"])
            for s in layout["sources"].values()
        },
        "roles": {
            role: sorted(layout["groups"][g] for g in groups)
            for role, groups in layout["roles"].items()
        },
    }
    return hashlib.sha256(
        json.dumps(canonical, sort_keys=True, ensure_ascii=False).encode()
    ).hexdigest()


def fetch(document: dict, cache: Path) -> bytes:
    """The original from its publisher, kept in a cache and refused when its digest differs."""
    path = cache / document["fileName"]
    if path.exists():
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() == document["sha256"]:
            return data
    request = urllib.request.Request(document["url"], headers={"User-Agent": "memoryos-benchmark"})
    with urllib.request.urlopen(request, timeout=300) as response:
        data = response.read()
    digest = hashlib.sha256(data).hexdigest()
    if digest != document["sha256"]:
        raise RuntimeError(
            f"{document['id']}: the publisher's file changed (sha256 {digest}); update the manifest"
        )
    cache.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return data


def _pages(api: Api, path: str, **query: object) -> Iterator[dict]:
    page = 0
    while True:
        body = api.get(path, page=page, size=100, **query)
        yield from body["items"]
        page += 1
        if page >= body["totalPages"]:
            return


def _items(api: Api, source_id: str) -> list[dict]:
    items, cursor = [], None
    while True:
        body = api.get(f"/api/sources/{source_id}/items", size=100, cursor=cursor)
        items.extend(body["items"])
        cursor = body.get("nextCursor")
        if not cursor:
            return items


def ensure_groups(api: Api, layout: dict, report: Report) -> dict[str, str]:
    ids = {}
    for key, name in layout["groups"].items():
        found = [g for g in _pages(api, "/api/groups", search=name) if g["name"] == name]
        if found:
            ids[key] = found[0]["id"]
        else:
            ids[key] = api.post("/api/groups", {"name": name})["id"]
            report.note(f"group created: {name}")
    return ids


def ensure_sources(
    api: Api, layout: dict, groups: dict[str, str], report: Report
) -> dict[str, str]:
    existing = {s["name"]: s for s in api.get("/api/sources")}
    ids = {}
    for key, spec in layout["sources"].items():
        wanted = sorted(groups[g] for g in spec["groups"])
        source = existing.get(spec["name"])
        if source is None:
            source = api.post(
                "/api/sources/file", {"name": spec["name"], "groupIds": wanted, "access": "PRIVATE"}
            )
            report.note(f"source created: {spec['name']}")
        else:
            if source["type"] != "FILE":
                raise RuntimeError(f"{spec['name']} exists and is not a FILE source")
            if source["access"] != "PRIVATE":
                api.post(f"/api/sources/{source['id']}/access", {"access": "PRIVATE"})
                report.note(f"source made private: {spec['name']}")
            current = sorted(
                g["id"] for g in api.get(f"/api/sources/{source['id']}/groups")["items"]
            )
            if current != wanted:
                api.post(f"/api/sources/{source['id']}/groups", {"groupIds": wanted})
                report.note(f"source groups set: {spec['name']}")
        ids[key] = source["id"]
    return ids


def ensure_documents(
    api: Api,
    manifest: dict,
    layout: dict,
    sources: dict[str, str],
    cache: Path,
    report: Report,
    prune: bool,
) -> None:
    for key, source_id in sources.items():
        name = layout["sources"][key]["name"]
        wanted = {d["sha256"]: d for d in manifest["documents"] if source_of(d) == key}
        present = {item["sha256"]: item for item in _items(api, source_id)}
        for digest, document in wanted.items():
            if digest in present:
                continue
            data = fetch(document, cache)
            authorization = api.post(
                f"/api/sources/{source_id}/uploads",
                {
                    "filename": document["fileName"],
                    "mediaType": MEDIA_TYPES[document["format"]],
                    "sizeBytes": len(data),
                    "sha256": digest,
                },
            )
            put_object(authorization["uploadUrl"], authorization["requiredHeaders"], data)
            api.post(f"/api/sources/{source_id}/uploads/{authorization['uploadId']}/finalize")
            report.note(f"uploaded {document['id']} to {name}")
        for digest, item in present.items():
            if digest not in wanted:
                if prune:
                    api.post(f"/api/sources/{source_id}/items/{item['id']}/remove")
                    report.note(f"removed {item['filename']} from {name}")
                else:
                    report.problem(
                        f"{name} holds {item['filename']}, which the manifest does not list"
                    )


def ensure_members(
    api: Api,
    layout: dict,
    groups: dict[str, str],
    actors: dict[str, str],
    report: Report,
    prune: bool,
) -> None:
    """Each role is in exactly the Groups the layout names; `actors` maps a role to its actor id."""
    benchmark = set(groups.values())
    membership: dict[str, set[str]] = {}
    names: dict[str, str] = {}
    # Every Group's members, one call per Group: a role's memberships outside the benchmark are what
    # would let it read documents the questions do not expect. Fine for a staging Tenant.
    for group in _pages(api, "/api/groups"):
        if group.get("systemKey"):
            continue
        names[group["id"]] = group["name"]
        for member in _pages(api, f"/api/groups/{group['id']}/members"):
            membership.setdefault(member["actorId"], set()).add(group["id"])
    for role, wanted_keys in layout["roles"].items():
        actor = actors[role]
        wanted = {groups[g] for g in wanted_keys}
        current = membership.get(actor, set())
        for group_id in wanted - current:
            api.post(f"/api/groups/{group_id}/members", {"actorIds": [actor]})
            report.note(f"role {role!r} added to {names.get(group_id, group_id)}")
        for group_id in current - wanted:
            label = names.get(group_id, group_id)
            if prune:
                api.post(f"/api/groups/{group_id}/members/{actor}/remove")
                report.note(f"role {role!r} removed from {label}")
            else:
                where = (
                    "another benchmark Group" if group_id in benchmark else "outside the benchmark"
                )
                report.would_remove.append(f"role {role!r} from {label} ({where})")
                report.problems.append(f"role {role!r} is also in {label}")


def _name(layout: dict, groups: dict[str, str], group_id: str) -> str:
    return next((layout["groups"][k] for k, v in groups.items() if v == group_id), "")


def corpus_problems(api: Api, manifest: dict, layout: dict) -> list[str]:
    """Every manifest document absent from its Source or not searchable there.

    A run on a corpus with any problem measures missing evidence, not retrieval: on 2026-10-05
    thirteen of 61 uploads failed extraction while the OCR service restarted, and 32 of 106
    answerable questions lost evidence.
    """
    existing = {s["name"]: s["id"] for s in api.get("/api/sources")}
    problems = []
    for key, spec in layout["sources"].items():
        source_id = existing.get(spec["name"])
        if source_id is None:
            problems.append(f"missing source: {spec['name']}")
            continue
        items = {item["sha256"]: item for item in _items(api, source_id)}
        for document in manifest["documents"]:
            if source_of(document) != key:
                continue
            item = items.get(document["sha256"])
            if item is None:
                problems.append(f"not uploaded: {document['id']} ({spec['name']})")
            elif item["status"] != "INDEXED" or item["searchStatus"] != "READY":
                problems.append(
                    f"not searchable: {document['id']} ({item['status']}/{item['searchStatus']},"
                    f" {item.get('searchErrorCode') or item.get('errorCode')})"
                )
    return problems


def wait_until_searchable(
    api: Api, layout: dict, sources: dict[str, str], timeout: float, report: Report
) -> None:
    deadline = time.monotonic() + timeout
    retried: set[str] = set()
    while True:
        waiting, failed = [], []
        for source_id in sources.values():
            for item in _items(api, source_id):
                # An extraction failure is often transient (a dependency restarting): retry it once.
                if item["status"] == "FAILED" and item["id"] not in retried:
                    api.post(f"/api/sources/{source_id}/items/{item['id']}/index-attempts")
                    retried.add(item["id"])
                    waiting.append(item["filename"])
                elif item["status"] == "FAILED" or item["searchStatus"] == "FAILED":
                    failed.append(
                        f"{item['filename']}"
                        f" ({item.get('searchErrorCode') or item.get('errorCode')})"
                    )
                elif item["searchStatus"] != "READY":
                    waiting.append(item["filename"])
        if not waiting:
            for line in failed:
                report.problem(f"not searchable: {line}")
            return
        if time.monotonic() > deadline:
            report.problem(f"still indexing after {int(timeout)}s: {len(waiting)} files")
            return
        print(f"indexing: {len(waiting)} files waiting", flush=True)
        time.sleep(30)


# What a benchmark agent copies from the built-in assistant, so only the layout's flags differ.
AGENT_FIELDS = (
    "instructions",
    "taskPrompt",
    "tools",
    "replaceBaseSystemPrompt",
    "contextTokenLimit",
    "outputTokenLimit",
    "modelConfigurationId",
)


def ensure_agents(api: Api, layout: dict, groups: dict[str, str], report: Report) -> dict[str, str]:
    """Benchmark agents: the built-in assistant with the layout's flags, shared per the layout."""
    personas, offset = [], 0
    while True:
        page = api.get("/api/chat/personas", view="ALL", offset=offset, limit=100)
        personas += page
        if len(page) < 100:
            break
        offset += 100
    builtin = next(p for p in personas if p.get("builtin"))
    ids = {}
    for key, spec in layout.get("agents", {}).items():
        body = {field: builtin.get(field) for field in AGENT_FIELDS}
        body.update(
            {
                "name": spec["name"],
                "description": "MEM-230 benchmark",
                "grounded": spec["grounded"],
                "sourceIds": [],
                "documentSetIds": [],
                "starterPrompts": [],
                "fileIds": [],
            }
        )
        existing = next(
            (p for p in personas if p["name"] == spec["name"] and not p.get("builtin")), None
        )
        if existing is None:
            existing = api.post("/api/chat/personas", body)
            report.note(f"agent created: {spec['name']}")
        elif any(
            existing.get(k) != v
            for k, v in body.items()
            if k in ("grounded", "instructions", "tools")
        ):
            existing = api.put(
                f"/api/chat/personas/{existing['id']}?revision={existing['revision']}", body
            )
            report.note(f"agent updated: {spec['name']}")
        wanted = sorted(groups[g] for g in spec["sharedWith"])
        current = sorted(s["group"]["id"] for s in existing.get("groupShares") or [])
        public = bool(spec.get("public"))
        if current != wanted or bool(existing.get("isPublic")) != public:
            api.put(
                f"/api/chat/personas/{existing['id']}/sharing?revision={existing['revision']}",
                {
                    "users": [],
                    "isPublic": public,
                    "publicPermission": "VIEWER",
                    "groups": [{"groupId": g, "permission": "VIEWER"} for g in wanted],
                },
            )
            report.note(f"agent shared: {spec['name']}")
        ids[key] = existing["id"]
    return ids


def provision(
    api: Api,
    manifest: dict,
    layout: dict,
    actors: dict[str, str],
    cache: Path,
    wait_seconds: float,
    prune: bool,
) -> Report:
    report = Report()
    groups = ensure_groups(api, layout, report)
    sources = ensure_sources(api, layout, groups, report)
    ensure_documents(api, manifest, layout, sources, cache, report, prune)
    ensure_members(api, layout, groups, actors, report, prune)
    ensure_agents(api, layout, groups, report)
    if wait_seconds > 0:
        wait_until_searchable(api, layout, sources, wait_seconds, report)
    return report
