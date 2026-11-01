"""Builds the Report 4 section I.3 ERDs (archify JSON) from a schema-only pg_dump.

Usage: python r4-erd.py <schema.sql> <area> <out.json>
The dump comes from staging: pg_dump --schema-only --no-owner --no-privileges -d memoryos.
"""
import json
import re
import sys

# Each area lists (table, grid row, grid column, extra columns to show besides keys).
AREAS = {
    "identity": {
        "title": "Identity and access",
        "tables": [
            ("tenant_invitations", 0, 0, ["email", "status", "expires_at"]),
            ("tenants", 0, 1, ["display_name", "status"]),
            ("audit_event", 0, 2, ["action", "event_class", "outcome", "occurred_at"]),
            ("actors", 1, 0, ["account_type", "ui_language"]),
            ("tenant_memberships", 1, 1, ["role", "status"]),
            ("iam_groups", 1, 2, ["name"]),
            ("external_identity_bindings", 2, 0, []),
            ("iam_group_memberships", 2, 1, ["is_manager"]),
            ("iam_group_capability_grants", 2, 2, []),
            ("actor_profiles", 3, 0, ["display_name", "email"]),
        ],
    },
    "sources": {
        "title": "Sources and documents",
        "tables": [
            ("google_drive_sources", 0, 0, ["scope_mode"]),
            ("sharepoint_sources", 0, 1, ["scope_mode", "tenant_host"]),
            ("lark_sources", 0, 2, []),
            ("credentials", 0, 3, ["name", "credential_kind", "status"]),
            ("source_group_grants", 1, 0, []),
            ("connector_credential_pairs", 1, 1, ["access_type", "status", "document_count"]),
            ("connectors", 1, 2, ["name", "connector_type", "status"]),
            ("connector_items", 1, 3, ["status", "provider_file_id"], ["connectors"]),
            ("document_set_source", 2, 0, []),
            ("index_attempts", 2, 1, ["status", "error_code"], ["connector_credential_pairs"]),
            ("documents_by_connector_credential_pair", 2, 2, [], ["connector_credential_pairs", "documents"]),
            ("connector_item_versions", 2, 3, ["revision_number", "filename", "size_bytes"]),
            ("document_set", 3, 0, ["name", "is_public"]),
            ("document_chunks", 3, 1, ["ordinal", "content", "token_count"]),
            ("documents", 3, 2, ["title", "status", "chunk_count"]),
            ("stored_objects", 3, 3, ["object_key", "filename", "size_bytes"]),
        ],
    },
    "chat": {
        "title": "Chat and AI administration",
        "tables": [
            ("chat_project", 0, 0, ["name", "instructions"]),
            ("chat_session", 0, 1, ["title", "archived_at", "deleted_at"]),
            ("chat_sharing", 0, 2, ["enabled"]),
            ("chat_settings", 0, 3, ["grounded_answers", "guardrail_topics", "blocked_phrases"]),
            ("chat_user_file", 1, 0, ["filename", "status"]),
            ("chat_message", 1, 1, ["role", "content", "status", "model_name"]),
            ("chat_feedback", 1, 2, ["positive", "comment"]),
            ("llm_provider", 1, 3, ["name", "adapter_type", "enabled"]),
            ("ai_usage_limit", 2, 1, ["scope", "token_budget", "cost_budget_usd", "period_days"]),
            ("ai_usage", 2, 2, ["day", "flow", "input_tokens", "output_tokens", "cost_usd"]),
            ("model_configuration", 2, 3, ["model_name", "display_name"]),
        ],
    },
    "meetings": {
        "title": "Meetings and MCP",
        "tables": [
            ("meeting_speaker", 0, 0, ["label", "name"]),
            ("meeting_user_share", 0, 1, []),
            ("mcp_server_group", 0, 3, []),
            ("meeting_utterance", 1, 0, ["meeting_id", "speaker", "start_ms", "end_ms", "text"], ["meeting_speaker"]),
            ("meeting", 1, 1, ["title", "kind", "status", "minutes_status"]),
            ("mcp_credential", 1, 2, ["status", "access_expires_at"]),
            ("mcp_server", 1, 3, ["slug", "name", "url", "auth_type", "status"]),
            ("meeting_minutes_item", 2, 0, ["meeting_id", "kind", "text", "owner", "due", "done"], ["meeting_utterance"]),
            ("meeting_group_share", 2, 1, []),
            ("mcp_server_tool", 2, 3, ["name", "policy", "read_only"]),
        ],
    },
}

MAX_COLUMNS = 9


def parse(sql):
    tables = {}
    for m in re.finditer(r"CREATE TABLE public\.(\w+) \((.*?)\n\);", sql, re.S):
        cols = []
        for line in m.group(2).split("\n"):
            line = line.strip().rstrip(",")
            c = re.match(r"(\w+) (.+?)(?: DEFAULT .*)?$", line)
            if not c or c.group(1) in ("CONSTRAINT", "PRIMARY", "UNIQUE", "CHECK", "FOREIGN"):
                continue
            ctype = re.sub(r" CONSTRAINT \w+| NOT NULL.*| GENERATED .*| COLLATE .*", "", c.group(2)).strip()
            cols.append({"name": c.group(1), "type": ctype, "not_null": "NOT NULL" in c.group(2)})
        tables[m.group(1)] = {"columns": cols, "pk": [], "uk": set(), "fk": {}}
    alter = re.finditer(r"ALTER TABLE ONLY public\.(\w+)\s+ADD CONSTRAINT \w+ (PRIMARY KEY|UNIQUE|FOREIGN KEY) \(([^)]*)\)(?: REFERENCES public\.(\w+)\(([^)]*)\))?", sql)
    for a in alter:
        t = tables.get(a.group(1))
        if not t:
            continue
        cols = [c.strip() for c in a.group(3).split(",")]
        if a.group(2) == "PRIMARY KEY":
            t["pk"] = cols
        elif a.group(2) == "UNIQUE":
            if len(cols) == 1:
                t["uk"].update(cols)
        elif a.group(4):
            # A composite key names one identifying column: the one that points at the target's id,
            # otherwise the last column (tenant_id only scopes the key). A column may carry several FKs; the diagram draws the one whose target it shows.
            pairs = list(zip(cols, [c.strip() for c in a.group(5).split(",")]))
            col, ref = next(((c, r) for c, r in pairs if r == "id"), pairs[-1])
            t["fk"].setdefault(col, []).append((a.group(4), ref))
    # a partial unique index (WHERE ...) does not make the column unique
    for u in re.finditer(r"CREATE UNIQUE INDEX \w+ ON public\.(\w+) USING \w+ \(([^)]*)\);", sql):
        t = tables.get(u.group(1))
        if t and "," not in u.group(2):
            t["uk"].add(u.group(2).strip())
    return tables


def short(ctype):
    return (ctype.replace("timestamp with time zone", "timestamptz")
            .replace("character varying", "varchar")
            .replace("timestamp without time zone", "timestamp"))


def build(tables, area):
    spec = AREAS[area]
    chosen = {t for t, *_ in spec["tables"]}
    entities, rels = [], []
    for name, row, col, extra, *only in spec["tables"]:
        only = set(only[0]) if only else None
        t = tables[name]
        attrs = []
        for c in t["columns"]:
            keys = []
            if c["name"] in t["pk"]:
                keys.append("pk")
            # badge every FK whose target is drawn; only draw lines for the allowed targets
            fks = t["fk"].get(c["name"], [])
            fk = (next((f for f in fks if f[0] in chosen and (only is None or f[0] in only)), None)
                  or next((f for f in fks if f[0] in chosen), None))
            if fk:
                keys.append("fk")
            if c["name"] in t["uk"] and "pk" not in keys:
                keys.append("uk")
            if not keys and c["name"] not in extra:
                continue
            a = {"name": c["name"], "type": short(c["type"])}
            if keys:
                a["key"] = keys[0] if len(keys) == 1 else keys
            if "fk" in keys:
                a["references"] = f"{fk[0]}.{fk[1]}"
            attrs.append(a)
        attrs = attrs[:MAX_COLUMNS]
        longest = max([len(a["name"]) + len(a["type"]) for a in attrs] + [len(name) + 4])
        width = max(250, round(longest * 8.6 + 96, -1))
        entities.append({"id": name, "label": name, "row": row, "col": col, "width": width, "attributes": attrs})
        for a in attrs:
            if "references" in a:
                target = a["references"].split(".")[0]
                if target == name or (only is not None and target not in only):
                    continue
                nullable = not next(c for c in t["columns"] if c["name"] == a["name"])["not_null"]
                rels.append({
                    "id": f"{name}-{a['name']}",
                    "from": target, "to": name,
                    "fromCardinality": "one", "toCardinality": "many",
                    "fromOptional": nullable,
                })
    seen, unique_rels = set(), []
    for r in rels:
        key = (r["from"], r["to"])
        if key in seen:
            continue
        seen.add(key)
        unique_rels.append(r)
    return {
        "schema_version": 1,
        "diagram_type": "erd",
        "meta": {"title": spec["title"], "locale": "en", "quality_profile": "showcase", "visual_preset": "editorial"},
        "layout": {"mode": "grid", "gapX": 80, "gapY": 48, "entityW": 250},
        "entities": entities,
        "relationships": unique_rels,
    }


if __name__ == "__main__":
    schema, area, out = sys.argv[1:4]
    doc = build(parse(open(schema, encoding="utf-8").read()), area)
    doc["meta"]["output"] = out.replace(".json", ".html")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(doc, f, indent=2)
        f.write("\n")
    print(len(doc["entities"]), "entities,", len(doc["relationships"]), "relationships")
