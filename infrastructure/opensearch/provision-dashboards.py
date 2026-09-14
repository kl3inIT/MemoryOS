#!/usr/bin/env python3
"""Reconcile MemoryOS Search saved objects through the Dashboards API."""
import json
import os
from pathlib import Path
import subprocess
import urllib.parse


CONTAINER = "memoryos-opensearch-dashboards"
OPENSEARCH_CONTAINER = "memoryos-opensearch"
SECRET_DIRECTORY = Path(os.environ.get("MEMORYOS_OPENSEARCH_SECRET_DIRECTORY", "/apps/memoryos/secrets/opensearch"))
BASE_URL = "https://localhost:5601/api/saved_objects"
INDEX_PATTERN_ID = "memoryos-chunks"
SAVED_SEARCH_ID = "memoryos-chunks-inspection"
INDEX_PATTERN = {
    "attributes": {
        "title": "memoryos-chunks*",
        "timeFieldName": "updated_at",
    }
}
SAVED_SEARCH = {
    "attributes": {
        "title": "MemoryOS chunks",
        "description": "Read-only inspection view for the MemoryOS Search projection.",
        "hits": 0,
        "columns": ["title", "content", "media_type", "updated_at"],
        "sort": [["updated_at", "desc"]],
        "version": 1,
        "kibanaSavedObjectMeta": {
            "searchSourceJSON": json.dumps({
                "highlightAll": True,
                "version": True,
                "query": {"query": "", "language": "kuery"},
                "filter": [],
                "indexRefName": "kibanaSavedObjectMeta.searchSourceJSON.index",
            }, separators=(",", ":"))
        },
    },
    "references": [{
        "name": "kibanaSavedObjectMeta.searchSourceJSON.index",
        "type": "index-pattern",
        "id": INDEX_PATTERN_ID,
    }],
}
FIELD_TYPES = {
    "keyword": "string", "text": "string", "match_only_text": "string", "date": "date", "boolean": "boolean",
    "long": "number", "integer": "number", "short": "number", "byte": "number", "float": "number",
    "double": "number", "half_float": "number", "scaled_float": "number", "ip": "ip", "nested": "nested",
    "object": "object",
}
DOC_VALUE_TYPES = {"keyword", "date", "boolean", "long", "integer", "short", "byte", "float", "double", "half_float",
                   "scaled_float", "ip"}


def request(method, object_type, object_id, body=None):
    path = "/" + urllib.parse.quote(object_type, safe="") + "/" + urllib.parse.quote(object_id, safe="")
    command = [
        "docker", "exec", "-i", CONTAINER, "curl",
        "--silent", "--show-error", "--max-time", "20",
        "--config", "/run/secrets/dashboards-bootstrap.curl",
        "--cacert", "/run/secrets/opensearch-ca.crt",
        "--header", "osd-xsrf: memoryos-operator",
        "--header", "securitytenant: global_tenant",
        "--header", "content-type: application/json",
        "--request", method,
        "--write-out", "\\n%{http_code}",
        BASE_URL + path,
    ]
    payload = None
    if body is not None:
        command.extend(["--data-binary", "@-"])
        payload = json.dumps(body, separators=(",", ":"))
    result = subprocess.run(command, input=payload, capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError("Dashboards API transport failed")
    try:
        response_body, raw_status = result.stdout.rsplit("\n", 1)
        status = int(raw_status)
        response = json.loads(response_body) if response_body else {}
    except (ValueError, json.JSONDecodeError) as error:
        raise RuntimeError("Dashboards API returned an invalid response") from error
    return status, response


def index_fields():
    """Read chunk field capabilities with the operator admin certificate.

    Dashboards normally refreshes and saves an index pattern's field list on first use, but the read-only inspector
    cannot save it, so Discover fails to resolve the time field unless the list is provisioned.
    """
    certificate = "".join((SECRET_DIRECTORY / name).read_text(encoding="utf-8") for name in ("admin.crt", "admin.key", "ca.crt"))
    script = ('umask 077; directory=$(mktemp -d) || exit 1; trap \'rm -rf "$directory"\' EXIT; '
              'cat > "$directory/admin.pem" && curl --silent --show-error --fail --max-time 20 '
              '--cert "$directory/admin.pem" --cacert "$directory/admin.pem" '
              '"https://localhost:9200/memoryos-chunks*/_field_caps?fields=*"')
    result = subprocess.run(["docker", "exec", "-i", OPENSEARCH_CONTAINER, "sh", "-c", script],
                            input=certificate, capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError("OpenSearch field capability read failed")
    try:
        capabilities = json.loads(result.stdout)["fields"]
    except (ValueError, KeyError, TypeError) as error:
        raise RuntimeError("OpenSearch returned invalid field capabilities") from error
    fields = []
    for name, by_type in sorted(capabilities.items()):
        types = sorted(by_type)
        field = {
            "name": name,
            "type": FIELD_TYPES.get(types[0], "unknown") if len(types) == 1 else "conflict",
            "esTypes": types,
            "count": 0,
            "scripted": False,
            "searchable": all(value.get("searchable", False) for value in by_type.values()),
            "aggregatable": all(value.get("aggregatable", False) for value in by_type.values()),
            "readFromDocValues": len(types) == 1 and types[0] in DOC_VALUE_TYPES,
        }
        if name.startswith("source_metadata."):
            field["subType"] = {"nested": {"path": "source_metadata"}}
        fields.append(field)
    if not any(field["name"] == INDEX_PATTERN["attributes"]["timeFieldName"] for field in fields):
        raise RuntimeError("chunk indices do not expose the index-pattern time field")
    return fields


def index_pattern_with_fields(fields):
    return {"attributes": {**INDEX_PATTERN["attributes"], "fields": json.dumps(fields, separators=(",", ":"))}}


def controlled_state(response, desired):
    actual_attributes = response.get("attributes", {})
    expected_attributes = desired["attributes"]
    attributes_match = all(actual_attributes.get(key) == value for key, value in expected_attributes.items())
    references_match = "references" not in desired or response.get("references", []) == desired["references"]
    return attributes_match and references_match


def reconcile(object_type, object_id, desired):
    status, current = request("GET", object_type, object_id)
    if status == 404:
        status, _ = request("POST", object_type, object_id, desired)
        if status not in (200, 201):
            raise RuntimeError("Dashboards saved-object creation failed: " + object_type)
    elif status == 200:
        if controlled_state(current, desired):
            return "unchanged"
        status, _ = request("PUT", object_type, object_id, desired)
        if status != 200:
            raise RuntimeError("Dashboards saved-object update failed: " + object_type)
    else:
        raise RuntimeError("Dashboards saved-object read failed: " + object_type)
    status, confirmed = request("GET", object_type, object_id)
    if status != 200 or confirmed.get("type") != object_type or confirmed.get("id") != object_id \
            or not controlled_state(confirmed, desired):
        raise RuntimeError("Dashboards saved-object verification failed: " + object_type)
    return "reconciled"


def main():
    if os.name == "posix" and os.geteuid() != 0:
        raise RuntimeError("run as the existing server Docker operator")
    fields = index_fields()
    index_pattern = reconcile("index-pattern", INDEX_PATTERN_ID, index_pattern_with_fields(fields))
    saved_search = reconcile("search", SAVED_SEARCH_ID, SAVED_SEARCH)
    print(json.dumps({
        "tenant": "global_tenant",
        "index_pattern": index_pattern,
        "index_pattern_fields": len(fields),
        "saved_search": saved_search,
        "discover_path": "/app/discover#/view/" + SAVED_SEARCH_ID,
    }))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Dashboards saved-object provisioning failed: " + str(error))
        raise SystemExit(1) from None
