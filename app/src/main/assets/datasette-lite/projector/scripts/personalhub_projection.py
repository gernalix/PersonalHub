#!/usr/bin/env python3
"""Asynchronous, incremental projection into a separate SQLite database with native PK/FK.

The write endpoint never calls this module and never depends on its availability.
"""
from __future__ import annotations

from contextlib import closing
import argparse
import base64
import hashlib
import json
import re
import sqlite3
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

from scripts.personalhub_model import EXCLUDED, TIMER_COLLECTIONS, snake
from scripts.personalhub_labels import DERIVED, LABEL_VERSION, human_label

LOGICAL_RELATIONS = {
    "app_state": [("current_session_id", "wordpulse_sessions", "id")],
    "correction_events": [("session_id", "wordpulse_sessions", "id"), ("original_word_entry_id", "word_entries", "id")],
    "quick_event_entries": [("template_id", "quick_event_templates", "id"), ("macro_id", "quick_event_macros", "id")],
    "quick_event_entry_field_values": [("entry_id", "quick_event_entries", "id"), ("field_id", "quick_event_template_fields", "id")],
    "quick_event_entry_tags": [("entry_id", "quick_event_entries", "id"), ("tag_id", "timer_tags", "id")],
    "quick_event_macro_actions": [("macro_id", "quick_event_macros", "id"), ("template_id", "quick_event_templates", "id")],
    "quick_event_macro_tags": [("macro_id", "quick_event_macros", "id"), ("tag_id", "timer_tags", "id")],
    "quick_event_template_fields": [("template_id", "quick_event_templates", "id")],
    "quick_event_template_tags": [("template_id", "quick_event_templates", "id"), ("tag_id", "timer_tags", "id")],
    "session_tags": [("session_id", "sessions", "id"), ("tag_id", "timer_tags", "id")],
    "snapshot_history": [("payload_id", "snapshot_payloads", "id"), ("base_history_id", "snapshot_history", "id")],
    # Cross-module references intentionally modelled outside Room's physical FK list.
    # They become real SQLite FKs in the read-only presentation so Datasette can render
    # labelled links and reverse "related rows" natively.
    "finance_transactions": [
        ("personId", "contacts", "id"),
        ("macroId", "finance_macros", "id"),
        ("recurrenceId", "finance_recurrences", "id"),
    ],
    "finance_recurrences": [
        ("personId", "contacts", "id"),
        ("placeId", "places", "uuid"),
        ("targetAccountId", "finance_accounts", "id"),
    ],
    "prescriptions": [
        ("doctor_contact_id", "contacts", "id"),
        ("finance_transaction_id", "finance_transactions", "id"),
    ],
    "intake_events": [("prescription_id", "prescriptions", "id")],
}
COLLECTION_KEYS = {
    "closedSessions": ["sessionId"], "tagSessions": ["tagId", "sessionId", "startTs", "endTs"],
    "activeSessionStart": ["sessionId"], "activeTagStart": ["sessionId", "tagId"],
    "tagParents": ["childId", "parentId"], "quickEventMacroActions": ["macroId", "templateId"],
}
TAG_COLLECTIONS = {"tasks", "lifePeriods", "timeFenceRules", "chronologySessions", "runningSessions", "quickEventTemplates", "quickEventEntries", "quickEventMacros"}

PRESENTATION_VERSION = 6
HUB_ENTITY_TARGETS = {
    ("people", "person"): ("contacts", "public_id", "person"),
    ("timer", "session"): ("sessions", "id", "timer_session"),
    ("places", "place"): ("places", "uuid", "place"),
    ("soldi", "transaction"): ("finance_transactions", "uuid", "transaction"),
    ("substances", "substance"): ("substances", "id", "substance"),
    ("substances", "intake"): ("intake_events", "id", "intake"),
    ("wordpulse", "word_session"): ("wordpulse_sessions", "id", "word_session"),
    ("hub", "resource"): ("hub_resources", "id", "resource"),
    # Health exposes these exact Hub entity kinds from the canonical Room model.
    ("salute", "event"): ("health_events", "id", "health_event"),
    ("salute", "sample"): ("health_samples", "id", "health_sample"),
    ("salute", "measurement"): ("health_measurements", "id", "health_measurement"),
    ("salute", "journal"): ("health_journal_entries", "id", "health_journal"),
}

WORDPULSE_BURST_GAP_MS = 5 * 60 * 1000
WORDPULSE_BURST_MIN_ENTRIES = 2
TEMPORAL_POINT_WINDOW_MS = 5 * 60 * 1000
TEMPORAL_POINT_HIGH_MS = 60 * 1000
TEMPORAL_MAX_CONTAINER_MS = 12 * 60 * 60 * 1000
TEMPORAL_MAX_INTERVAL_MS = 24 * 60 * 60 * 1000
TEMPORAL_ENDPOINT_SPECS = {
    "place": ("places", "visit", "places"),
    "timer_session": ("timer", "session", "sessions"),
    "transaction": ("soldi", "transaction", "finance_transactions"),
    "intake": ("substances", "intake", "intake_events"),
    "wordpulse_burst": ("wordpulse", "activity_burst", "wordpulse_activity_bursts"),
    # People itself is intentionally not inferred from time. Only concrete People
    # events participate; their native contact FK remains the route to the person.
    "contact_event": ("people", "contact_event", "contact_events"),
    "contact_initiative": ("people", "contact_initiative", "contact_initiatives"),
    "health_event": ("salute", "event", "health_events"),
}


def q(value):
    return '"' + value.replace('"', '""') + '"'


def json_text(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def normalized_context_text(value):
    return re.sub(r"\s+", " ", str(value or "").strip()).casefold()


def label_field(fields):
    return next((name for name in ("name", "title", "nickname", "label", "original_word", "value", "key", "namespace", "summary", "message", "session_title", "tag_name") if name in fields), "label")


def time_field(name, kind):
    return kind == "INTEGER" and (re.search(r"(?:_at|_for)(?:_utc)?_ms$", name) or name.endswith("_at") or name in {"timestamp_ms", "timestamp", "start_ms", "end_ms", "expected_end_ms", "start_ts", "end_ts", "ts_ms", "first_check_in_at_place", "first_check_in_at_global"})


def models(schema_path):
    result = {}
    for entity in json.loads(Path(schema_path).read_text())["database"]["entities"]:
        name = entity["tableName"]
        if name in EXCLUDED:
            continue
        fields = {f["columnName"]: f["affinity"] for f in entity["fields"]}
        relations = [(fk["columns"], fk["table"], fk["referencedColumns"]) for fk in entity.get("foreignKeys", [])]
        relations += [
            ([col], table, [key])
            for col, table, key in LOGICAL_RELATIONS.get(name, [])
            if col in fields
        ]
        result[name] = dict(fields=fields, keys=entity["primaryKey"]["columnNames"], relations=relations, label=label_field(fields))
    for collection, columns in TIMER_COLLECTIONS.items():
        name = "timer_" + snake(collection)
        fields = {snake(c): ("INTEGER" if c in {"id", "sessionId", "tagId", "childId", "parentId", "templateId", "macroId", "fieldId", "entryId"} or c.endswith(("Ms", "Ts", "Count", "Minutes", "Index", "Order")) or c.startswith("is") else "TEXT") for c in columns.split()}
        relations = []
        for field, target in (("template_id", "timer_quick_event_templates"), ("macro_id", "timer_quick_event_macros"), ("field_id", "timer_quick_event_field_definitions"), ("entry_id", "timer_quick_event_entries"), ("session_id", "timer_chronology_sessions"), ("tag_id", "timer_tags"), ("child_id", "timer_tags"), ("parent_id", "timer_tags")):
            if field in fields:
                relations.append(([field], target, ["id"]))
        result[name] = dict(fields=fields, keys=[snake(k) for k in COLLECTION_KEYS.get(collection, ["id"])], relations=relations, label=label_field(fields))
        if collection in TAG_COLLECTIONS:
            result[name + "_tags"] = dict(fields={"owner": "TEXT", "tag_id": "INTEGER"}, keys=["owner", "tag_id"], relations=[(["owner"], name, ["record_id"]), (["tag_id"], "timer_tags", ["id"])], label="label")
    result["timer_chain_steps"] = dict(fields={"chain_id": "INTEGER", "position": "INTEGER", "name": "TEXT", "link": "TEXT"}, keys=["chain_id", "position"], relations=[(["chain_id"], "timer_chains", ["id"])], label="name")
    result["timer_chain_step_tags"] = dict(fields={"owner": "TEXT", "tag_id": "INTEGER"}, keys=["owner", "tag_id"], relations=[(["owner"], "timer_chain_steps", ["record_id"]), (["tag_id"], "timer_tags", ["id"])], label="label")
    result["timer_active_chain_run"] = dict(fields={"id": "INTEGER", "chain_id": "INTEGER", "step_index": "INTEGER", "current_session_id": "INTEGER"}, keys=["id"], relations=[(["chain_id"], "timer_chains", ["id"]), (["current_session_id"], "timer_chronology_sessions", ["id"])], label="label")
    result["timer_tag_restored_sessions"] = dict(fields={"tag_id": "INTEGER", "session_id": "INTEGER"}, keys=["tag_id", "session_id"], relations=[(["tag_id"], "timer_tags", ["id"]), (["session_id"], "timer_chronology_sessions", ["id"])], label="label")
    # Referenced contacts need a real semantic label column, derived from their name fields.
    if "contacts" in result:
        result["contacts"]["label"] = "name"
    for name in DERIVED & result.keys():
        result[name]["label"] = "display_label"
    return result


def identity(db, model, table, device, payload):
    parts = []
    for field in model[table]["keys"]:
        value = payload.get(field)
        kind = model[table]["fields"][field]
        if value is not None:
            if kind == "INTEGER": value = int(value)
            elif kind == "REAL": value = float(value)
            elif kind == "TEXT": value = str(value)
        parts.append(db.execute("SELECT hex(quote(?))", (value,)).fetchone()[0])
    key = ":".join(parts)
    return str(uuid.UUID(bytes=hashlib.md5(f"personalhub:{device}:{table}:{key}".encode()).digest(), version=3))


def prepare(db, model):
    db.execute("PRAGMA foreign_keys=ON")
    db.execute("PRAGMA journal_mode=WAL")
    db.execute("CREATE TABLE IF NOT EXISTS devices(id TEXT PRIMARY KEY, name TEXT NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS projection_checkpoint(id INTEGER PRIMARY KEY CHECK(id=1), epoch TEXT NOT NULL, revision INTEGER NOT NULL, model_hash TEXT NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS projection_sources(sync_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, deleted INTEGER)")
    db.execute("CREATE TABLE IF NOT EXISTS projection_records(source_id TEXT NOT NULL, table_name TEXT NOT NULL, record_id TEXT NOT NULL, fingerprint TEXT NOT NULL, PRIMARY KEY(table_name,record_id))")
    db.execute("CREATE INDEX IF NOT EXISTS projection_records_source ON projection_records(source_id)")
    for name, spec in model.items():
        fields = dict(spec["fields"])
        fields.setdefault(spec["label"], "TEXT")
        for column, kind in spec["fields"].items():
            if time_field(column, kind): fields[column+"_utc"] = "TEXT"
            if column.endswith("_epoch_day"): fields[column+"_date"] = "TEXT"
            if kind == "BLOB": fields[column+"_size"] = "INTEGER"
        relations = []
        for columns, target, target_keys in spec["relations"]:
            if target not in model:
                raise ValueError("Missing referenced model")
            ref = columns[0] if len(columns) == 1 else "_".join(columns) + "_reference"
            for column in columns:
                fields[column + "_source_id"] = fields[column]
            fields[ref] = "TEXT"
            relations.append(f"FOREIGN KEY ({q(ref)}) REFERENCES {q(target)}(record_id)")
        spec["presentation_fields"] = fields
        definitions = ["record_id TEXT PRIMARY KEY", "source_device TEXT NOT NULL REFERENCES devices(id)", "state TEXT NOT NULL", "replica_updated_at TEXT"]
        definitions += [q(column)+" "+kind for column, kind in fields.items()]
        definitions += relations
        db.execute(f"CREATE TABLE IF NOT EXISTS {q(name)} ("+", ".join(definitions)+")")
        # Add derived-label columns to an existing valid presentation without rebuilding data.
        existing = {row[1] for row in db.execute(f"PRAGMA table_info({q(name)})")}
        for column, kind in fields.items():
            if column not in existing: db.execute(f"ALTER TABLE {q(name)} ADD COLUMN {q(column)} {kind}")
        for columns, _, _ in spec["relations"]:
            ref = columns[0] if len(columns) == 1 else "_".join(columns) + "_reference"
            db.execute(f"CREATE INDEX IF NOT EXISTS {q('fk_'+name+'_'+ref)} ON {q(name)}({q(ref)})")
    prepare_hub_entity_relations(db, model)
    prepare_temporal_tables(db, model)
    db.commit()


def hub_relation_specs(model):
    return {
        key: spec
        for key, spec in HUB_ENTITY_TARGETS.items()
        if spec[0] in model
    }


def prepare_hub_entity_relations(db, model):
    required = {"hub_entity_bindings", "hub_contexts", "hub_context_members"}
    if not required <= model.keys():
        return False
    specs = hub_relation_specs(model)
    if not specs:
        return False

    # Retire older derived prototypes. These tables are presentation-only and rebuildable.
    db.execute("DROP TABLE IF EXISTS hub_person_relations")
    existing = {row[1] for row in db.execute("PRAGMA table_info(hub_entity_relations)")}
    expected = {"id", "left_binding", "right_binding", "context_count", "duplicate_context_count", "temporal_support_count", "max_temporal_confidence", "max_overlap_ratio"}
    for _, (_, _, slug) in specs.items():
        expected.update({"left_" + slug, "right_" + slug})
    if existing and not expected <= existing:
        db.execute("DROP TABLE IF EXISTS hub_entity_relation_contexts")
        db.execute("DROP TABLE IF EXISTS hub_entity_relations")

    definitions = [
        "id TEXT PRIMARY KEY",
        "label TEXT NOT NULL",
        "left_binding TEXT NOT NULL REFERENCES hub_entity_bindings(record_id)",
        "right_binding TEXT NOT NULL REFERENCES hub_entity_bindings(record_id)",
        "left_module TEXT NOT NULL",
        "left_entity_kind TEXT NOT NULL",
        "right_module TEXT NOT NULL",
        "right_entity_kind TEXT NOT NULL",
        "left_role TEXT NOT NULL",
        "right_role TEXT NOT NULL",
        "context_count INTEGER NOT NULL",
        "duplicate_context_count INTEGER NOT NULL",
        "temporal_support_count INTEGER NOT NULL",
        "max_temporal_confidence TEXT",
        "max_overlap_ratio REAL",
    ]
    seen = set()
    for _, (table, _, slug) in specs.items():
        if slug in seen:
            continue
        seen.add(slug)
        definitions.append(f"{q('left_'+slug)} TEXT REFERENCES {q(table)}(record_id)")
        definitions.append(f"{q('right_'+slug)} TEXT REFERENCES {q(table)}(record_id)")

    db.execute("CREATE TABLE IF NOT EXISTS hub_entity_relations (" + ", ".join(definitions) + ")")
    db.execute(
        "CREATE TABLE IF NOT EXISTS hub_entity_relation_contexts ("
        "relation_id TEXT NOT NULL REFERENCES hub_entity_relations(id) ON DELETE CASCADE,"
        "context_id TEXT NOT NULL REFERENCES hub_contexts(record_id),"
        "context_fingerprint TEXT NOT NULL,"
        "duplicate INTEGER NOT NULL,"
        "PRIMARY KEY(relation_id,context_id))"
    )
    db.execute("CREATE INDEX IF NOT EXISTS hub_entity_relations_left_binding ON hub_entity_relations(left_binding)")
    db.execute("CREATE INDEX IF NOT EXISTS hub_entity_relations_right_binding ON hub_entity_relations(right_binding)")
    db.execute("CREATE INDEX IF NOT EXISTS hub_entity_relation_contexts_context ON hub_entity_relation_contexts(context_id)")
    for slug in seen:
        db.execute(f"CREATE INDEX IF NOT EXISTS {q('hub_entity_relations_left_'+slug)} ON hub_entity_relations({q('left_'+slug)})")
        db.execute(f"CREATE INDEX IF NOT EXISTS {q('hub_entity_relations_right_'+slug)} ON hub_entity_relations({q('right_'+slug)})")
    return True


def resolve_hub_target(db, model, binding):
    spec = hub_relation_specs(model).get((binding["module_id"], binding["entity_kind"]))
    if not spec:
        return None
    table, canonical_column, slug = spec
    label_column = model[table]["label"]
    row = db.execute(
        f"SELECT record_id,{q(label_column)} FROM {q(table)} "
        f"WHERE source_device=? AND {q(canonical_column)}=? AND state!='deleted' LIMIT 1",
        (binding["source_device"], binding["canonical_id"]),
    ).fetchone()
    if row is None:
        return None
    return slug, row[0], row[1] or binding["canonical_id"]


def refresh_hub_entity_relations(db, model):
    if not prepare_hub_entity_relations(db, model):
        return 0
    context_type = (
        "c.context_type_id"
        if "context_type_id" in model["hub_contexts"]["fields"]
        else "NULL"
    )
    members = db.execute(
        f"""
        SELECT m.context_id,m.role,m.position,
               {context_type} AS context_type_id,c.title,
               b.record_id AS binding_record_id,b.source_device,
               b.module_id,b.entity_kind,b.canonical_id
        FROM hub_context_members m
        JOIN hub_entity_bindings b ON b.record_id=m.entity_id
        JOIN hub_contexts c ON c.record_id=m.context_id
        WHERE m.state='active' AND b.state='active' AND b.lifecycle!='DELETED' AND c.state='active'
        ORDER BY m.context_id,m.position,m.entity_id,m.role
        """
    ).fetchall()
    by_context = {}
    for member in members:
        by_context.setdefault(member["context_id"], []).append(member)

    db.execute("DELETE FROM hub_entity_relation_contexts")
    db.execute("DELETE FROM hub_entity_relations")
    resolved = {}

    def resolve(member):
        key = member["binding_record_id"]
        if key not in resolved:
            resolved[key] = resolve_hub_target(db, model, member)
        return resolved[key]

    edges = {}
    for context_id, context_members in by_context.items():
        context_fingerprint = hashlib.sha256(
            json_text([
                context_members[0]["context_type_id"] if context_members else None,
                normalized_context_text(context_members[0]["title"]) if context_members else "",
                sorted((member["binding_record_id"], member["role"] or "") for member in context_members),
            ]).encode()
        ).hexdigest()
        resolved_members = [
            (member, resolve(member))
            for member in context_members
            if resolve(member) is not None
        ]
        for left_index in range(len(resolved_members)):
            for right_index in range(left_index + 1, len(resolved_members)):
                first_member, first = resolved_members[left_index]
                second_member, second = resolved_members[right_index]
                first_key = (first_member["binding_record_id"], first_member["role"] or "")
                second_key = (second_member["binding_record_id"], second_member["role"] or "")
                if first_key == second_key:
                    continue
                if second_key < first_key:
                    first_member, second_member = second_member, first_member
                    first, second = second, first
                    first_key, second_key = second_key, first_key

                left_slug, left_record, left_label = first
                right_slug, right_record, right_label = second
                relation_id = hashlib.sha256(json_text([first_key, second_key]).encode()).hexdigest()
                edge = edges.setdefault(
                    relation_id,
                    {
                        "values": {
                            "id": relation_id,
                            "label": (
                                f"{first_member['module_id']} · {left_label} ↔ "
                                f"{second_member['module_id']} · {right_label}"
                            ),
                            "left_binding": first_member["binding_record_id"],
                            "right_binding": second_member["binding_record_id"],
                            "left_module": first_member["module_id"],
                            "left_entity_kind": first_member["entity_kind"],
                            "right_module": second_member["module_id"],
                            "right_entity_kind": second_member["entity_kind"],
                            "left_role": first_member["role"] or "",
                            "right_role": second_member["role"] or "",
                            "left_" + left_slug: left_record,
                            "right_" + right_slug: right_record,
                        },
                        "contexts": [],
                    },
                )
                edge["contexts"].append((context_id, context_fingerprint))

    for relation_id, edge in edges.items():
        fingerprints = []
        for _, fingerprint in edge["contexts"]:
            if fingerprint not in fingerprints:
                fingerprints.append(fingerprint)
        edge["values"]["context_count"] = len(fingerprints)
        edge["values"]["duplicate_context_count"] = len(edge["contexts"]) - len(fingerprints)
        edge["values"]["temporal_support_count"] = 0
        edge["values"]["max_temporal_confidence"] = None
        edge["values"]["max_overlap_ratio"] = None
        values = edge["values"]
        db.execute(
            "INSERT INTO hub_entity_relations(" + ",".join(q(key) for key in values) + ") "
            "VALUES (" + ",".join("?" for _ in values) + ")",
            tuple(values.values()),
        )
        seen_fingerprints = set()
        for context_id, fingerprint in edge["contexts"]:
            duplicate = 1 if fingerprint in seen_fingerprints else 0
            seen_fingerprints.add(fingerprint)
            db.execute(
                "INSERT INTO hub_entity_relation_contexts(relation_id,context_id,context_fingerprint,duplicate) VALUES (?,?,?,?)",
                (relation_id, context_id, fingerprint, duplicate),
            )
    return len(edges)



def temporal_endpoint_specs(model):
    result = {}
    for slug, (module, kind, table) in TEMPORAL_ENDPOINT_SPECS.items():
        if table == "wordpulse_activity_bursts":
            if "word_entries" in model:
                result[slug] = (module, kind, table)
        elif table in model:
            result[slug] = (module, kind, table)
    return result


def prepare_temporal_tables(db, model):
    specs = temporal_endpoint_specs(model)
    if "word_entries" in model:
        burst_existing = {row[1] for row in db.execute("PRAGMA table_info(wordpulse_activity_bursts)")}
        if burst_existing and "record_id" not in burst_existing:
            db.execute("DROP TABLE IF EXISTS hub_temporal_evidence")
            db.execute("DROP TABLE IF EXISTS hub_temporal_relations")
            db.execute("DROP TABLE IF EXISTS wordpulse_activity_bursts")
        definitions = [
            "record_id TEXT PRIMARY KEY",
            "source_device TEXT NOT NULL REFERENCES devices(id)",
            "label TEXT NOT NULL",
            "start_ms INTEGER NOT NULL",
            "end_ms INTEGER NOT NULL",
            "entry_count INTEGER NOT NULL",
            "first_entry TEXT NOT NULL REFERENCES word_entries(record_id)",
            "last_entry TEXT NOT NULL REFERENCES word_entries(record_id)",
        ]
        if "wordpulse_sessions" in model:
            definitions.append("session TEXT REFERENCES wordpulse_sessions(record_id)")
        else:
            definitions.append("session TEXT")
        db.execute("CREATE TABLE IF NOT EXISTS wordpulse_activity_bursts (" + ", ".join(definitions) + ")")
        db.execute("CREATE INDEX IF NOT EXISTS wordpulse_activity_bursts_time ON wordpulse_activity_bursts(source_device,start_ms,end_ms)")

    if len(specs) < 2:
        return False

    existing = {row[1] for row in db.execute("PRAGMA table_info(hub_temporal_relations)")}
    expected = {
        "id", "label", "relation_kind", "confidence", "left_module", "right_module",
        "left_event_key", "right_event_key", "left_start_ms", "right_start_ms",
        "time_distance_ms",
    }
    for slug in specs:
        expected.update({"left_" + slug, "right_" + slug})
    if existing and not expected <= existing:
        db.execute("DROP TABLE IF EXISTS hub_temporal_evidence")
        db.execute("DROP TABLE IF EXISTS hub_temporal_relations")

    definitions = [
        "id TEXT PRIMARY KEY",
        "label TEXT NOT NULL",
        "relation_kind TEXT NOT NULL",
        "confidence TEXT NOT NULL",
        "left_module TEXT NOT NULL",
        "left_entity_kind TEXT NOT NULL",
        "right_module TEXT NOT NULL",
        "right_entity_kind TEXT NOT NULL",
        "left_event_key TEXT NOT NULL",
        "right_event_key TEXT NOT NULL",
        "left_start_ms INTEGER NOT NULL",
        "left_end_ms INTEGER NOT NULL",
        "right_start_ms INTEGER NOT NULL",
        "right_end_ms INTEGER NOT NULL",
        "overlap_ms INTEGER",
        "overlap_ratio REAL",
        "delta_start_ms INTEGER",
        "delta_end_ms INTEGER",
        "time_distance_ms INTEGER NOT NULL",
    ]
    for slug, (_, _, table) in specs.items():
        definitions.append(f"{q('left_'+slug)} TEXT REFERENCES {q(table)}(record_id)")
        definitions.append(f"{q('right_'+slug)} TEXT REFERENCES {q(table)}(record_id)")
    db.execute("CREATE TABLE IF NOT EXISTS hub_temporal_relations (" + ", ".join(definitions) + ")")
    for slug in specs:
        db.execute(f"CREATE INDEX IF NOT EXISTS {q('hub_temporal_relations_left_'+slug)} ON hub_temporal_relations({q('left_'+slug)})")
        db.execute(f"CREATE INDEX IF NOT EXISTS {q('hub_temporal_relations_right_'+slug)} ON hub_temporal_relations({q('right_'+slug)})")
    db.execute("CREATE INDEX IF NOT EXISTS hub_temporal_relations_confidence ON hub_temporal_relations(confidence,relation_kind)")

    # Evidence is deliberately non-relational to the endpoints: rows suppressed because
    # a native FK/Context already explains the pair must not create duplicate backlinks.
    db.execute(
        "CREATE TABLE IF NOT EXISTS hub_temporal_evidence ("
        "id TEXT PRIMARY KEY,"
        "visible_relation_id TEXT,"
        "explicit_relation_id TEXT,"
        "suppression_reason TEXT,"
        "left_table TEXT NOT NULL,left_record TEXT NOT NULL,left_event_key TEXT NOT NULL,"
        "right_table TEXT NOT NULL,right_record TEXT NOT NULL,right_event_key TEXT NOT NULL,"
        "relation_kind TEXT NOT NULL,confidence TEXT NOT NULL,"
        "left_start_ms INTEGER NOT NULL,left_end_ms INTEGER NOT NULL,"
        "right_start_ms INTEGER NOT NULL,right_end_ms INTEGER NOT NULL,"
        "overlap_ms INTEGER,overlap_ratio REAL,delta_start_ms INTEGER,delta_end_ms INTEGER,"
        "time_distance_ms INTEGER NOT NULL)"
    )
    db.execute("CREATE INDEX IF NOT EXISTS hub_temporal_evidence_explicit ON hub_temporal_evidence(explicit_relation_id,suppression_reason)")
    return True


def parse_utc_ms(value):
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return int(value)
    text = str(value).strip()
    if not text:
        return None
    if re.fullmatch(r"-?\d+", text):
        return int(text)
    try:
        parsed = datetime.fromisoformat(text.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp() * 1000)
    except (TypeError, ValueError, OverflowError, OSError):
        return None


def refresh_wordpulse_bursts(db, model):
    if "word_entries" not in model:
        return 0
    fields = model["word_entries"]["fields"]
    if "created_at_utc_ms" not in fields and "submitted_at_utc_ms" not in fields:
        return 0
    db.execute("DELETE FROM wordpulse_activity_bursts")
    submitted = "submitted_at_utc_ms" if "submitted_at_utc_ms" in fields else "NULL"
    created = "created_at_utc_ms" if "created_at_utc_ms" in fields else "NULL"
    session = "session_id" if "session_id" in fields else "NULL"
    rows = db.execute(
        f"SELECT record_id,source_device,{session} AS session_id,"
        f"COALESCE({submitted},{created}) AS at_ms "
        "FROM word_entries WHERE state='active' AND COALESCE("
        f"{submitted},{created}) IS NOT NULL ORDER BY source_device,at_ms,record_id"
    ).fetchall()
    bursts = []
    current = []
    for row in rows:
        if current:
            previous = current[-1]
            split = (
                row["source_device"] != previous["source_device"]
                or row["at_ms"] - previous["at_ms"] > WORDPULSE_BURST_GAP_MS
                or row["session_id"] != previous["session_id"]
            )
            if split:
                bursts.append(current)
                current = []
        current.append(row)
    if current:
        bursts.append(current)

    inserted = 0
    for burst in bursts:
        if len(burst) < WORDPULSE_BURST_MIN_ENTRIES:
            continue
        first, last = burst[0], burst[-1]
        burst_id = hashlib.sha256(
            json_text([first["source_device"], first["record_id"], last["record_id"]]).encode()
        ).hexdigest()
        start_ms, end_ms = int(first["at_ms"]), int(last["at_ms"])
        label = f"WordPulse · {len(burst)} entries · " + datetime.fromtimestamp(
            start_ms / 1000, timezone.utc
        ).isoformat(timespec="minutes").replace("+00:00", "Z")
        db.execute(
            "INSERT INTO wordpulse_activity_bursts"
            "(record_id,source_device,label,start_ms,end_ms,entry_count,first_entry,last_entry,session)"
            " VALUES (?,?,?,?,?,?,?,?,?)",
            (
                burst_id, first["source_device"], label, start_ms, end_ms, len(burst),
                first["record_id"], last["record_id"], first["session_id"],
            ),
        )
        inserted += 1
    return inserted


def temporal_nodes(db, model):
    nodes = []

    def add(slug, device, record_id, event_key, start_ms, end_ms, label):
        if start_ms is None:
            return
        try:
            start_ms = int(start_ms)
            end_ms = start_ms if end_ms is None else int(end_ms)
        except (TypeError, ValueError):
            return
        if end_ms < start_ms:
            return
        if end_ms > start_ms and end_ms - start_ms > TEMPORAL_MAX_INTERVAL_MS:
            return
        module, kind, table = temporal_endpoint_specs(model)[slug]
        nodes.append({
            "slug": slug, "module": module, "kind": kind, "table": table,
            "device": device, "record": record_id, "event_key": str(event_key),
            "start": start_ms, "end": end_ms, "label": str(label or record_id),
        })

    if {"places", "place_events"} <= model.keys():
        rows = db.execute(
            """
            SELECT p.source_device,p.record_id,p.nickname,e.session_uuid,
                   MIN(CASE WHEN e.event_type='CHECK_IN' THEN e.timestamp END) AS start_ms,
                   MIN(CASE WHEN e.event_type='CHECK_OUT' THEN e.timestamp END) AS end_ms
            FROM place_events e
            JOIN places p ON p.record_id=e.place_id
            WHERE e.state='active' AND p.state='active'
              AND e.session_uuid IS NOT NULL AND trim(e.session_uuid)!=''
            GROUP BY p.source_device,p.record_id,p.nickname,e.session_uuid
            HAVING MIN(CASE WHEN e.event_type='CHECK_IN' THEN e.timestamp END) IS NOT NULL
               AND MIN(CASE WHEN e.event_type='CHECK_OUT' THEN e.timestamp END) IS NOT NULL
            """
        ).fetchall()
        for row in rows:
            add("place", row["source_device"], row["record_id"], row["session_uuid"], row["start_ms"], row["end_ms"], row["nickname"])

    if "sessions" in model and {"start_ms", "end_ms"} <= model["sessions"]["fields"].keys():
        deleted_clause = " AND deleted_at_ms IS NULL" if "deleted_at_ms" in model["sessions"]["fields"] else ""
        for row in db.execute(
            "SELECT record_id,source_device,title,start_ms,end_ms FROM sessions "
            "WHERE state='active' AND start_ms IS NOT NULL AND end_ms IS NOT NULL" + deleted_clause
        ).fetchall():
            add("timer_session", row["source_device"], row["record_id"], row["record_id"], row["start_ms"], row["end_ms"], row["title"])

    if "finance_transactions" in model and "occurredAt" in model["finance_transactions"]["fields"]:
        label = model["finance_transactions"]["label"]
        for row in db.execute(
            f"SELECT record_id,source_device,occurredAt,{q(label)} AS item_label "
            "FROM finance_transactions WHERE state='active' AND occurredAt IS NOT NULL"
        ).fetchall():
            at_ms = parse_utc_ms(row["occurredAt"])
            add("transaction", row["source_device"], row["record_id"], row["record_id"], at_ms, at_ms, row["item_label"])

    if "intake_events" in model and "timestamp_ms" in model["intake_events"]["fields"]:
        label = model["intake_events"]["label"]
        for row in db.execute(
            f"SELECT record_id,source_device,timestamp_ms,{q(label)} AS item_label "
            "FROM intake_events WHERE state='active' AND timestamp_ms IS NOT NULL"
        ).fetchall():
            add("intake", row["source_device"], row["record_id"], row["record_id"], row["timestamp_ms"], row["timestamp_ms"], row["item_label"])

    if "word_entries" in model:
        for row in db.execute(
            "SELECT record_id,source_device,label,start_ms,end_ms FROM wordpulse_activity_bursts"
        ).fetchall():
            add("wordpulse_burst", row["source_device"], row["record_id"], row["record_id"], row["start_ms"], row["end_ms"], row["label"])

    if "contact_events" in model and "occurred_at" in model["contact_events"]["fields"]:
        label = model["contact_events"]["label"]
        for row in db.execute(
            f"SELECT record_id,source_device,occurred_at,{q(label)} AS item_label "
            "FROM contact_events WHERE state='active' AND occurred_at IS NOT NULL"
        ).fetchall():
            add("contact_event", row["source_device"], row["record_id"], row["record_id"], row["occurred_at"], row["occurred_at"], row["item_label"])

    if "contact_initiatives" in model and "timestamp_utc" in model["contact_initiatives"]["fields"]:
        label = model["contact_initiatives"]["label"]
        for row in db.execute(
            f"SELECT record_id,source_device,timestamp_utc,{q(label)} AS item_label "
            "FROM contact_initiatives WHERE state='active' AND timestamp_utc IS NOT NULL"
        ).fetchall():
            add("contact_initiative", row["source_device"], row["record_id"], row["record_id"], row["timestamp_utc"], row["timestamp_utc"], row["item_label"])

    if "health_events" in model and "occurred_at_ms" in model["health_events"]["fields"]:
        label = model["health_events"]["label"]
        for row in db.execute(
            f"SELECT record_id,source_device,occurred_at_ms,{q(label)} AS item_label "
            "FROM health_events WHERE state='active' AND occurred_at_ms IS NOT NULL"
        ).fetchall():
            add("health_event", row["source_device"], row["record_id"], row["record_id"], row["occurred_at_ms"], row["occurred_at_ms"], row["item_label"])

    return nodes


def endpoint_pair_key(left_table, left_record, right_table, right_record):
    endpoints = sorted(((left_table, left_record), (right_table, right_record)))
    return tuple(endpoints)


def explicit_relation_pairs(db, model):
    native = set()
    for source, spec in model.items():
        for columns, target, _ in spec["relations"]:
            ref = columns[0] if len(columns) == 1 else "_".join(columns) + "_reference"
            if ref not in spec.get("presentation_fields", {}):
                continue
            for row in db.execute(
                f"SELECT record_id,{q(ref)} AS target_record FROM {q(source)} "
                f"WHERE state='active' AND {q(ref)} IS NOT NULL"
            ).fetchall():
                native.add(endpoint_pair_key(source, row["record_id"], target, row["target_record"]))

    contexts = {}
    if "hub_entity_relations" in {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}:
        slug_to_table = {spec[2]: spec[0] for spec in hub_relation_specs(model).values()}
        for row in db.execute("SELECT * FROM hub_entity_relations").fetchall():
            left = next(((slug_to_table[slug], row["left_"+slug]) for slug in slug_to_table if row["left_"+slug] is not None), None)
            right = next(((slug_to_table[slug], row["right_"+slug]) for slug in slug_to_table if row["right_"+slug] is not None), None)
            if left and right:
                contexts[endpoint_pair_key(left[0], left[1], right[0], right[1])] = row["id"]
    return native, contexts


def temporal_match(left, right):
    left_interval = left["end"] > left["start"]
    right_interval = right["end"] > right["start"]

    if left_interval and right_interval:
        overlap = min(left["end"], right["end"]) - max(left["start"], right["start"])
        if overlap <= 0:
            return None
        left_duration = left["end"] - left["start"]
        right_duration = right["end"] - right["start"]
        denominator = min(left_duration, right_duration)
        ratio = overlap / denominator if denominator else 0.0
        if ratio < 0.5:
            return None
        duration_ratio = max(left_duration, right_duration) / denominator if denominator else float("inf")
        return {
            "relation_kind": "interval_overlap",
            "confidence": "high" if ratio >= 0.8 and duration_ratio <= 4.0 else "medium",
            "overlap_ms": overlap,
            "overlap_ratio": ratio,
            "delta_start_ms": abs(left["start"] - right["start"]),
            "delta_end_ms": abs(left["end"] - right["end"]),
            "time_distance_ms": 0,
        }

    if left_interval != right_interval:
        interval = left if left_interval else right
        point = right if left_interval else left
        duration = interval["end"] - interval["start"]
        if duration > TEMPORAL_MAX_CONTAINER_MS or not (interval["start"] <= point["start"] <= interval["end"]):
            return None
        return {
            "relation_kind": "occurred_during",
            "confidence": "high" if duration <= 3 * 60 * 60 * 1000 else "medium",
            "overlap_ms": None,
            "overlap_ratio": None,
            "delta_start_ms": abs(point["start"] - interval["start"]),
            "delta_end_ms": abs(interval["end"] - point["start"]),
            "time_distance_ms": 0,
        }

    distance = abs(left["start"] - right["start"])
    if distance > TEMPORAL_POINT_WINDOW_MS:
        return None
    return {
        "relation_kind": "near_in_time",
        "confidence": "high" if distance <= TEMPORAL_POINT_HIGH_MS else "medium",
        "overlap_ms": None,
        "overlap_ratio": None,
        "delta_start_ms": distance,
        "delta_end_ms": distance,
        "time_distance_ms": distance,
    }


def confidence_rank(value):
    return {"low": 0, "medium": 1, "high": 2}.get(value, -1)


def update_context_temporal_support(db, relation_id, metrics):
    row = db.execute(
        "SELECT temporal_support_count,max_temporal_confidence,max_overlap_ratio "
        "FROM hub_entity_relations WHERE id=?", (relation_id,)
    ).fetchone()
    if row is None:
        return
    best_confidence = row["max_temporal_confidence"]
    if confidence_rank(metrics["confidence"]) > confidence_rank(best_confidence):
        best_confidence = metrics["confidence"]
    ratio = row["max_overlap_ratio"]
    if metrics["overlap_ratio"] is not None:
        ratio = metrics["overlap_ratio"] if ratio is None else max(ratio, metrics["overlap_ratio"])
    db.execute(
        "UPDATE hub_entity_relations SET temporal_support_count=?,max_temporal_confidence=?,max_overlap_ratio=? WHERE id=?",
        (row["temporal_support_count"] + 1, best_confidence, ratio, relation_id),
    )


def refresh_temporal_relations(db, model):
    if not prepare_temporal_tables(db, model):
        return 0
    refresh_wordpulse_bursts(db, model)
    db.execute("DELETE FROM hub_temporal_evidence")
    db.execute("DELETE FROM hub_temporal_relations")
    has_context_relations = "hub_entity_relations" in {
        row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }
    if has_context_relations:
        db.execute(
            "UPDATE hub_entity_relations SET temporal_support_count=0,max_temporal_confidence=NULL,max_overlap_ratio=NULL"
        )

    native_pairs, context_pairs = explicit_relation_pairs(db, model)
    specs = temporal_endpoint_specs(model)
    nodes = sorted(temporal_nodes(db, model), key=lambda item: (item["device"], item["start"], item["end"], item["event_key"]))
    visible = 0
    active = []
    for node in nodes:
        active = [
            other for other in active
            if other["device"] == node["device"] and other["end"] >= node["start"] - TEMPORAL_POINT_WINDOW_MS
        ]
        for other in active:
            if other["module"] == node["module"]:
                continue
            metrics = temporal_match(other, node)
            if metrics is None:
                continue
            ordered = sorted((other, node), key=lambda item: (item["table"], item["record"], item["event_key"]))
            left, right = ordered
            evidence_id = hashlib.sha256(
                json_text([
                    left["table"], left["record"], left["event_key"],
                    right["table"], right["record"], right["event_key"],
                    metrics["relation_kind"],
                ]).encode()
            ).hexdigest()
            pair = endpoint_pair_key(left["table"], left["record"], right["table"], right["record"])
            explicit_relation_id = context_pairs.get(pair)
            suppression_reason = "context" if explicit_relation_id else ("native_fk" if pair in native_pairs else None)
            visible_relation_id = None

            if suppression_reason is None:
                visible_relation_id = evidence_id
                values = {
                    "id": visible_relation_id,
                    "label": (
                        f"{left['module']} · {left['label']} ↔ {right['module']} · {right['label']} "
                        f"· {metrics['relation_kind']}"
                    ),
                    "relation_kind": metrics["relation_kind"],
                    "confidence": metrics["confidence"],
                    "left_module": left["module"],
                    "left_entity_kind": left["kind"],
                    "right_module": right["module"],
                    "right_entity_kind": right["kind"],
                    "left_event_key": left["event_key"],
                    "right_event_key": right["event_key"],
                    "left_start_ms": left["start"],
                    "left_end_ms": left["end"],
                    "right_start_ms": right["start"],
                    "right_end_ms": right["end"],
                    "overlap_ms": metrics["overlap_ms"],
                    "overlap_ratio": metrics["overlap_ratio"],
                    "delta_start_ms": metrics["delta_start_ms"],
                    "delta_end_ms": metrics["delta_end_ms"],
                    "time_distance_ms": metrics["time_distance_ms"],
                    "left_" + left["slug"]: left["record"],
                    "right_" + right["slug"]: right["record"],
                }
                db.execute(
                    "INSERT OR IGNORE INTO hub_temporal_relations(" + ",".join(q(key) for key in values) + ") "
                    "VALUES (" + ",".join("?" for _ in values) + ")",
                    tuple(values.values()),
                )
                visible += 1
            elif explicit_relation_id:
                update_context_temporal_support(db, explicit_relation_id, metrics)

            db.execute(
                "INSERT OR REPLACE INTO hub_temporal_evidence VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    evidence_id, visible_relation_id, explicit_relation_id, suppression_reason,
                    left["table"], left["record"], left["event_key"],
                    right["table"], right["record"], right["event_key"],
                    metrics["relation_kind"], metrics["confidence"],
                    left["start"], left["end"], right["start"], right["end"],
                    metrics["overlap_ms"], metrics["overlap_ratio"],
                    metrics["delta_start_ms"], metrics["delta_end_ms"],
                    metrics["time_distance_ms"],
                ),
            )
        active.append(node)
    return visible


def placeholder(db, model, table, record_id, device):
    label = model[table]["label"]
    db.execute(f"INSERT OR IGNORE INTO {q(table)}(record_id,source_device,state,{q(label)}) VALUES (?,?,?,?)", (record_id, device, "missing", human_label(table, {}, record_id, label, "missing")))


def records(model, db, envelope):
    table, device = envelope["entity_type"], envelope["device_id"]
    payload = json.loads(envelope["payload_json"])
    if table not in model:
        raise ValueError("Unrecognized source entity type")
    yield table, envelope["sync_id"], payload
    if table != "snapshot" or envelope["deleted_at_ms"] is not None or not payload.get("json"):
        return
    snapshot = json.loads(payload["json"])
    for collection in TIMER_COLLECTIONS:
        table = "timer_" + snake(collection)
        for original in snapshot.get(collection, []):
            if original.get("isDeleted") or original.get("deletedAtMs"):
                continue
            value = {snake(k): v for k, v in original.items()}
            record_id = identity(db, model, table, device, value)
            yield table, record_id, value
            if collection in TAG_COLLECTIONS:
                for tag in set(original.get("tagIds", [])):
                    edge = dict(owner=record_id, tag_id=tag)
                    yield table+"_tags", identity(db, model, table+"_tags", device, edge), edge
            if collection == "tags":
                for session in set(original.get("restoreSessionIds", [])):
                    edge = dict(tag_id=original["id"], session_id=session)
                    yield "timer_tag_restored_sessions", identity(db, model, "timer_tag_restored_sessions", device, edge), edge
            if collection == "chains":
                for position, step in enumerate(original.get("steps", [])):
                    value = dict(chain_id=original["id"], position=position, name=step.get("name"), link=step.get("link"))
                    step_id = identity(db, model, "timer_chain_steps", device, value)
                    yield "timer_chain_steps", step_id, value
                    for tag in set(step.get("tagIds", [])):
                        edge = dict(owner=step_id, tag_id=tag)
                        yield "timer_chain_step_tags", identity(db, model, "timer_chain_step_tags", device, edge), edge
    if snapshot.get("activeChainRun"):
        value = {snake(k): v for k, v in snapshot["activeChainRun"].items()}
        value["id"] = 1
        yield "timer_active_chain_run", identity(db, model, "timer_active_chain_run", device, value), value


def store_record(db, model, envelope, table, record_id, value, *, force=False):
    spec, device = model[table], envelope["device_id"]
    state = "deleted" if envelope["deleted_at_ms"] is not None else "active"
    digest = hashlib.sha256(json_text([value, state, envelope["updated_at"]]).encode()).hexdigest()
    previous = db.execute("SELECT fingerprint FROM projection_records WHERE table_name=? AND record_id=?", (table, record_id)).fetchone()
    if previous and previous[0] == digest and not force:
        return
    placeholder(db, model, table, record_id, device)
    output = {}
    if state == "active" or value:
        for field in spec["fields"]:
            item = value.get(field)
            if isinstance(item, dict) and item.get("encoding") == "base64":
                item = base64.b64decode(item["data"], validate=True)
            elif isinstance(item, (dict, list)):
                item = json_text(item)
            output[field] = item
            if time_field(field, spec["fields"][field]):
                try: output[field+"_utc"] = datetime.fromtimestamp(item/1000, timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z") if item is not None else None
                except (TypeError, ValueError, OverflowError, OSError): output[field+"_utc"] = None
            if field.endswith("_epoch_day"):
                try: output[field+"_date"] = datetime.fromtimestamp(item*86400, timezone.utc).date().isoformat() if item is not None else None
                except (ValueError, OverflowError, OSError): output[field+"_date"] = None
            if isinstance(item, bytes): output[field+"_size"] = len(item)
        for columns, target, target_keys in spec["relations"]:
            ref = columns[0] if len(columns) == 1 else "_".join(columns)+"_reference"
            values = [value.get(col) for col in columns]
            for column, item in zip(columns, values): output[column+"_source_id"] = item
            if all(v is not None for v in values):
                target_id = values[0] if target_keys == ["record_id"] else identity(db, model, target, device, dict(zip(target_keys, values)))
                placeholder(db, model, target, target_id, device)
                output[ref] = target_id
            else:
                output[ref] = None
    label = spec["label"]
    label_values = value if value else dict(db.execute(f"SELECT * FROM {q(table)} WHERE record_id=?", (record_id,)).fetchone())
    if table == "contacts":
        old_name = db.execute("SELECT name FROM contacts WHERE record_id=?", (record_id,)).fetchone()[0]
        label_values = dict(label_values, name=old_name)
    output[label] = human_label(table, label_values, record_id, label, state)
    output.update(state=state, replica_updated_at=envelope["updated_at"])
    db.execute(f"UPDATE {q(table)} SET "+", ".join(q(k)+"=?" for k in output)+" WHERE record_id=?", (*output.values(), record_id))
    db.execute("INSERT OR REPLACE INTO projection_records VALUES (?,?,?,?)", (envelope["sync_id"], table, record_id, digest))


def refresh_labels(db, model):
    if {"contacts", "contact_fields"} <= model.keys():
        db.execute("UPDATE contacts SET name=COALESCE((SELECT value FROM contact_fields WHERE contact_fields.contact_id=contacts.record_id AND contact_fields.state='active' AND field_type IN ('name','nickname') AND trim(value)!='' ORDER BY CASE field_type WHEN 'name' THEN 0 ELSE 1 END,position LIMIT 1),'Unnamed contact · #'||substr(record_id,1,8)) WHERE state='active'")
    if {"interaction_rules", "substances"} <= model.keys():
        db.execute("UPDATE interaction_rules SET label='Interaction: '||COALESCE((SELECT name FROM substances WHERE record_id=interaction_rules.source_substance_id),'substance')||' · '||COALESCE(avoid_before_hours,0)||'h before / '||COALESCE(avoid_after_hours,0)||'h after · #'||substr(record_id,1,8) WHERE state='active'")


def mark_deleted(db, model, table, record_id):
    row = db.execute(f"SELECT * FROM {q(table)} WHERE record_id=?", (record_id,)).fetchone()
    if row:
        label = model[table]["label"]
        db.execute(f"UPDATE {q(table)} SET state='deleted',{q(label)}=? WHERE record_id=?", (human_label(table, dict(row), record_id, label, "deleted"), record_id))


def project(source, output, schema, *, reconcile=False):
    model = models(schema)
    with closing(sqlite3.connect(output, timeout=30)) as target, target:
        target.row_factory = sqlite3.Row
        prepare(target, model)
        model_hash = hashlib.sha256(Path(schema).read_bytes() + f"{LABEL_VERSION}:{PRESENTATION_VERSION}".encode()).hexdigest()
        checkpoint = target.execute("SELECT epoch,revision,model_hash FROM projection_checkpoint WHERE id=1").fetchone()
        with closing(sqlite3.connect(f"file:{Path(source).resolve()}?mode=ro", uri=True, timeout=5)) as origin, origin:
            origin.row_factory = sqlite3.Row
            origin.execute("BEGIN")
            clock = origin.execute("SELECT epoch,revision FROM personalhub_projection_clock WHERE id=1").fetchone()
            if clock is None: raise ValueError("Prepare the PersonalHub envelope before projecting")
            epoch, revision = clock
            full = reconcile or checkpoint is None or checkpoint[0] != epoch or checkpoint[1] > revision or checkpoint[2] != model_hash
            if not full and checkpoint[1] == revision:
                return dict(changed=0, tables=len(model), foreign_keys=0)
            if full:
                changed = [r[0] for r in origin.execute("SELECT sync_id FROM personalhub_entities")]
                known = {r[0] for r in target.execute("SELECT sync_id FROM projection_sources")}
            else:
                # Server revisions are commit ordered, including equal/out-of-order client times.
                changed = [r[0] for r in origin.execute("SELECT sync_id FROM personalhub_projection_changes WHERE revision>? AND revision<=?", (checkpoint[1], revision))]
                known = set(changed)
            rows = []
            for start in range(0, len(changed), 400):
                chunk = changed[start:start+400]
                rows.extend(dict(r) for r in origin.execute("SELECT * FROM personalhub_entities WHERE sync_id IN ("+",".join("?" for _ in chunk)+")", chunk))
        removed = known - {r["sync_id"] for r in rows}
        target.execute("BEGIN IMMEDIATE")
        for envelope in rows:
            device = envelope["device_id"]
            target.execute("INSERT OR IGNORE INTO devices VALUES (?,?)", (device, "Installation "+device[:8]))
            emitted = set()
            for table, record_id, value in records(model, target, envelope):
                emitted.add((table, record_id))
                store_record(target, model, envelope, table, record_id, value, force=full)
            previous = target.execute("SELECT table_name,record_id FROM projection_records WHERE source_id=?", (envelope["sync_id"],)).fetchall()
            for table, record_id in previous:
                if (table, record_id) not in emitted:
                    mark_deleted(target, model, table, record_id)
                    target.execute("DELETE FROM projection_records WHERE table_name=? AND record_id=?", (table, record_id))
            target.execute("INSERT OR REPLACE INTO projection_sources VALUES (?,?,?)", (envelope["sync_id"], envelope["updated_at_ms"], envelope["deleted_at_ms"]))
        for source_id in removed:
            for table, record_id in target.execute("SELECT table_name,record_id FROM projection_records WHERE source_id=?", (source_id,)).fetchall():
                mark_deleted(target, model, table, record_id)
            target.execute("DELETE FROM projection_sources WHERE sync_id=?", (source_id,))
            target.execute("DELETE FROM projection_records WHERE source_id=?", (source_id,))
        refresh_labels(target, model)
        refresh_hub_entity_relations(target, model)
        refresh_temporal_relations(target, model)
        if full:
            # Upgrade old generic placeholders too; they may have no source envelope yet.
            for table, spec in model.items():
                for row in target.execute(f"SELECT * FROM {q(table)} WHERE state IN ('missing','deleted')").fetchall():
                    label = human_label(table, dict(row) if row["state"] == "deleted" else {}, row["record_id"], spec["label"], row["state"])
                    target.execute(f"UPDATE {q(table)} SET {q(spec['label'])}=? WHERE record_id=?", (label, row["record_id"]))
        target.execute("INSERT OR REPLACE INTO projection_checkpoint VALUES (1,?,?,?)", (epoch, revision, model_hash))
        if list(target.execute("PRAGMA foreign_key_check")):
            raise RuntimeError("Projection foreign-key check failed")
        target.commit()
        return dict(changed=len(rows)+len(removed), tables=len(model), foreign_keys=0)


def configure(config_path, metadata_path, schema):
    import yaml
    model = models(schema)
    config = yaml.safe_load(Path(config_path).read_text()) or {}
    exposed_tables = ["devices", "projection_sources", "projection_records", "projection_checkpoint", *model]
    if {"hub_entity_bindings", "hub_contexts", "hub_context_members"} <= model.keys() and hub_relation_specs(model):
        exposed_tables.extend(["hub_entity_relations", "hub_entity_relation_contexts"])
    if len(temporal_endpoint_specs(model)) >= 2:
        exposed_tables.extend(["hub_temporal_relations", "hub_temporal_evidence"])
    if "word_entries" in model:
        exposed_tables.append("wordpulse_activity_bursts")
    tables = {name: {"permissions": {"view-table": {"id": "root"}, "insert-row": False, "update-row": False, "delete-row": False, "alter-table": False, "drop-table": False}} for name in exposed_tables}
    config.setdefault("databases", {})["personalhub_read"] = {"permissions": {"view-database": {"id": "root"}, "execute-sql": {"id": "root"}, "execute-write-sql": False, "create-table": False}, "tables": tables}
    Path(config_path).write_text(yaml.safe_dump(config, sort_keys=False))
    metadata = json.loads(Path(metadata_path).read_text())
    entries = {"devices": {"label_column": "name", "title": "Source installations"}}
    for name, spec in model.items():
        fields = spec["fields"]
        hidden = ["record_id", "source_device"] + [f for f in fields if f in spec["keys"] and all(f not in relation[0] for relation in spec["relations"])]
        hidden += [c+"_source_id" for cols, _, _ in spec["relations"] for c in cols]
        hidden += [c for c, kind in fields.items() if kind == "BLOB" or c == "json" or c.endswith("_json")]
        hidden += [c for c, kind in fields.items() if time_field(c, kind) or c.endswith("_epoch_day")]
        entries[name] = {"label_column": spec["label"], "title": name.replace("_", " ").title(), "hidden_columns": hidden, "description": "Read-only PersonalHub projection. Foreign keys link to related records. Source identifiers and payloads remain preserved; state distinguishes active, deleted and missing records."}
    if "hub_entity_relations" in exposed_tables:
        entries["hub_entity_relations"] = {
            "title": "Related across PersonalHub",
            "label_column": "label",
            "hidden_columns": ["id", "left_binding", "right_binding", "duplicate_context_count"],
            "description": "Deduplicated symmetric read-only graph derived from existing PersonalHub Context memberships. Each unordered entity pair appears once; native foreign keys expose both endpoints. Context provenance is preserved separately.",
        }
        entries["hub_entity_relation_contexts"] = {
            "title": "Relationship provenance",
            "hidden_columns": ["context_fingerprint"],
            "description": "Original Contexts supporting each cross-module relation. Exact duplicate Context signatures are retained as provenance but marked duplicate so they never duplicate the visible relation.",
        }
    if "hub_temporal_relations" in exposed_tables:
        entries["hub_temporal_relations"] = {
            "title": "Temporal associations",
            "label_column": "label",
            "hidden_columns": ["id", "left_event_key", "right_event_key"],
            "description": "Read-only inferred relationships based only on temporal evidence. They are intentionally separate from native foreign keys and explicit Context relationships; confidence describes temporal strength, not semantic certainty.",
        }
        entries["hub_temporal_evidence"] = {"hidden": True}
    if "wordpulse_activity_bursts" in exposed_tables:
        entries["wordpulse_activity_bursts"] = {
            "title": "WordPulse activity bursts",
            "label_column": "label",
            "hidden_columns": ["record_id", "source_device"],
            "description": "Derived groups of at least two WordPulse entries separated by no more than five minutes. Long-lived WordPulse session boundaries are not used as temporal evidence.",
        }
    entries["projection_sources"] = {"hidden": True}
    entries["projection_records"] = {"hidden": True}
    entries["projection_checkpoint"] = {"hidden": True}
    metadata.setdefault("databases", {})["personalhub_read"] = {"title": "PersonalHub", "tables": entries}
    metadata["databases"]["personalhub"] = {"title": "PersonalHub sync", "tables": {name: {"hidden": True} for name in ("personalhub_entities", "personalhub_projection_clock", "personalhub_projection_changes")}}
    Path(metadata_path).write_text(json.dumps(metadata, indent=2))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("--config", type=Path)
    parser.add_argument("--metadata", type=Path)
    parser.add_argument("--watch", action="store_true")
    parser.add_argument("--reconcile", action="store_true", help="Re-read all envelopes, including manual restore/repair changes")
    args = parser.parse_args()
    if bool(args.config) != bool(args.metadata):
        parser.error("--config and --metadata must be supplied together")
    failures = 0
    next_reconcile = time.monotonic() + 86400
    while True:
        try:
            result = project(args.source, args.output, args.schema, reconcile=args.reconcile or time.monotonic() >= next_reconcile)
            if args.reconcile or time.monotonic() >= next_reconcile: next_reconcile = time.monotonic() + 86400
            args.reconcile = False
            if args.config:
                configure(args.config, args.metadata, args.schema)
                args.config = None
            if not args.watch or failures: print("Projection ready; foreign keys valid", flush=True)
            failures = 0
            if not args.watch: return
        except Exception:
            if not failures: print("Projection failed; last good presentation retained; sync endpoint unaffected", flush=True)
            failures += 1
            if not args.watch: raise SystemExit(1)
        time.sleep(min(300, 5 * 2 ** min(failures, 6)))


if __name__ == "__main__":
    main()
