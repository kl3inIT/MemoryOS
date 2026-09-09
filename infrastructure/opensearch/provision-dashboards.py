#!/usr/bin/env python3
"""Reconcile MemoryOS Search saved objects through the Dashboards API."""
import json
import os
import subprocess
import urllib.parse


CONTAINER = "memoryos-opensearch-dashboards"
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
    index_pattern = reconcile("index-pattern", INDEX_PATTERN_ID, INDEX_PATTERN)
    saved_search = reconcile("search", SAVED_SEARCH_ID, SAVED_SEARCH)
    print(json.dumps({
        "tenant": "global_tenant",
        "index_pattern": index_pattern,
        "saved_search": saved_search,
        "discover_path": "/app/discover#/view/" + SAVED_SEARCH_ID,
    }))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("Dashboards saved-object provisioning failed: " + str(error))
        raise SystemExit(1) from None
