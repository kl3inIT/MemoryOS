"""Builds the Report 4 section I.3 table blocks (Column, Type, Constraints, Notes) from a schema-only pg_dump.

Usage: python r4-tables.py <schema.sql> <out.json>
Covers every table drawn in the ERDs (AREAS in r4-erd.py). Hand-written table descriptions and
column notes come from r4-table-notes.json next to this script; allowed values come from CHECK constraints.
"""
import importlib.util
import json
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).parent

# Columns and enum values of features outside the capstone scope (chat channels and bots, agents, deep research).
OUT_OF_SCOPE_COLUMNS = {
    "chat_session.persona_id", "chat_session.surface", "chat_session.channel_connection_id",
    "chat_session.channel_chat_id", "chat_session.channel_public", "mcp_server.public_bots",
    "chat_message.research_plan", "chat_message.research_agents", "chat_settings.deep_research_enabled",
}
# Reading order in the report: the main table of each area first, then the tables that hang off it.
DOC_ORDER = {
    "identity": ["tenants", "actors", "actor_profiles", "external_identity_bindings", "tenant_memberships",
                 "tenant_invitations", "iam_groups", "iam_group_memberships", "iam_group_capability_grants",
                 "audit_event"],
    "sources": ["connectors", "credentials", "connector_credential_pairs", "google_drive_sources",
                "sharepoint_sources", "lark_sources", "source_group_grants", "connector_items",
                "connector_item_versions", "stored_objects", "index_attempts", "documents",
                "documents_by_connector_credential_pair", "document_chunks", "document_set", "document_set_source"],
    "chat": ["chat_project", "chat_session", "chat_message", "chat_feedback", "chat_sharing", "chat_user_file",
             "chat_settings", "llm_provider", "model_configuration", "ai_usage", "ai_usage_limit"],
    "meetings": ["meeting", "meeting_speaker", "meeting_utterance", "meeting_minutes_item", "meeting_user_share",
                 "meeting_group_share", "mcp_server", "mcp_server_tool", "mcp_server_group", "mcp_credential"],
}
OUT_OF_SCOPE_VALUES ={"CHANNELS_MANAGE", "AGENTS_CREATE", "AGENTS_MANAGE", "AGENT_BUILDER", "DEEP_RESEARCH"}
spec = importlib.util.spec_from_file_location("r4erd", HERE / "r4-erd.py")
r4erd = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r4erd)


def default_of(raw):
    d = re.search(r" DEFAULT (.+?)(?: CONSTRAINT \w+)?(?: NOT NULL)?$", raw)
    if not d:
        return None
    v = d.group(1)
    v = re.sub(r"::[\w ]+(\(\d+\))?(\[\])?", "", v)
    return {"CURRENT_TIMESTAMP": "NOW()", "now()": "NOW()"}.get(v, v)


def columns(sql, table):
    body = re.search(rf"CREATE TABLE public\.{table} \((.*?)\n\);", sql, re.S).group(1)
    # split on top-level commas: a CHECK may span several lines
    items, depth, cur = [], 0, ""
    for ch in body:
        if ch == "," and depth == 0:
            items.append(cur)
            cur = ""
            continue
        depth += {"(": 1, ")": -1}.get(ch, 0)
        cur += ch
    items.append(cur)
    cols, checks = [], []
    for line in items:
        line = " ".join(line.split())
        if line.startswith("CONSTRAINT"):
            checks.append(line)
            continue
        c = re.match(r"(\w+|\"\w+\") (.+)$", line)
        if not c:
            continue
        name = c.group(1).strip('"')
        ctype = re.sub(r" DEFAULT .*| CONSTRAINT \w+| NOT NULL.*| GENERATED .*| COLLATE .*", "", c.group(2)).strip()
        cols.append({"name": name, "type": ctype, "not_null": "NOT NULL" in c.group(2),
                     "default": default_of(c.group(2)), "generated": "GENERATED" in c.group(2)})
    return cols, checks


def allowed_values(checks, col):
    for ck in checks:
        # only a CHECK that is nothing but "col IN (...)", not one that ties the column to others
        m = re.match(rf"CONSTRAINT \w+ CHECK \(\(\({re.escape(col)}\)::text = ANY \(\(ARRAY\[(.*?)\]\)", ck)
        if m and ck.count("ANY") == 1:
            return [v for v in re.findall(r"'([^']*)'", m.group(1)) if v not in OUT_OF_SCOPE_VALUES]
        # (status = 'A') OR (status = 'B')
        simple = rf"\(\({re.escape(col)}\)::text = '[^']*'::text\)"
        if re.fullmatch(rf"CONSTRAINT \w+ CHECK \(\({simple}(?: OR {simple})*\)\)", ck):
            return re.findall(r"'([^']*)'::text\)", ck)
    return None


def sql_type(t):
    return r4erd.short(t).upper()


def build(sql):
    tables = r4erd.parse(sql)
    notes = json.loads((HERE / "r4-table-notes.json").read_text(encoding="utf-8"))
    blocks = []
    for area, spec_ in r4erd.AREAS.items():
        for name in DOC_ORDER[area]:
            t = tables[name]
            cols, checks = columns(sql, name)
            rows = []
            for c in cols:
                if f"{name}.{c['name']}" in OUT_OF_SCOPE_COLUMNS:
                    continue
                cons = []
                if c["name"] in t["pk"]:
                    cons.append("PRIMARY KEY" if len(t["pk"]) == 1 else "PRIMARY KEY (part)")
                for target, ref in t["fk"].get(c["name"], []):
                    cons.append(f"FK → {target}({ref})")
                if c["not_null"] and c["name"] not in t["pk"]:
                    cons.append("NOT NULL")
                if c["name"] in t["uk"]:
                    cons.append("UNIQUE")
                if c["default"] is not None:
                    cons.append(f"DEFAULT {c['default']}")
                if c["generated"]:
                    cons.append("GENERATED")
                note = notes["columns"].get(f"{name}.{c['name']}") or notes["columns"].get(f"*.{c['name']}", "")
                values = allowed_values(checks, c["name"])
                if values and not all(re.search(rf"\b{re.escape(v)}\b", note) for v in values):
                    note = (note + "; " if note else "") + "One of: " + ", ".join(values)
                rows.append([c["name"], sql_type(c["type"]), ", ".join(cons) or "", note])
            blocks.append({"area": spec_["title"], "table": name,
                           "description": notes["tables"].get(name, ""), "rows": rows})
    return blocks


if __name__ == "__main__":
    schema, out = sys.argv[1:3]
    blocks = build(open(schema, encoding="utf-8").read())
    pathlib.Path(out).write_text(json.dumps(blocks, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(len(blocks), "tables,", sum(len(b["rows"]) for b in blocks), "columns")
