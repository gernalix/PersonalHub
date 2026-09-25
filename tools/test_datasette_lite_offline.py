#!/usr/bin/env python3
from __future__ import annotations

import hashlib
import importlib
import json
import sqlite3
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "app/src/main/assets/datasette-lite"
SCHEMA = ROOT / "core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase/23.json"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def test_manifest() -> int:
    manifest = json.loads((ASSET / "vendor-manifest.json").read_text())
    checked = 0
    for section in (manifest["pyodide"]["files"], manifest["datasette"]["wheels"], manifest["projector"]["files"]):
        for item in section:
            base = "pyodide" if "package" in item else ("wheels" if item["file"].endswith(".whl") else "")
            path = ASSET / base / item["file"] if base else ASSET / item["file"]
            assert path.is_file(), path
            assert sha256(path) == item["sha256"], path
            assert path.stat().st_size == item["bytes"], path
            checked += 1
    assert (ASSET / "projector/schema.json").read_bytes() == SCHEMA.read_bytes()
    return checked


def test_no_runtime_network() -> None:
    for name in ("index.html", "webworker.js"):
        text = (ASSET / name).read_text()
        scrubbed = text.replace("http://localhost", "")
        assert "https://" not in scrubbed, name
        assert "http://" not in scrubbed, name

def entity(schema: dict, name: str) -> dict:
    return next(item for item in schema["database"]["entities"] if item["tableName"] == name)


def test_projection() -> dict[str, object]:
    projector_root = ASSET / "projector"
    sys.path.insert(0, str(projector_root))
    local_envelope = importlib.import_module("scripts.personalhub_local_envelope")
    projection = importlib.import_module("scripts.personalhub_projection")
    schema = json.loads(SCHEMA.read_text())
    root = Path(tempfile.mkdtemp(prefix="personalhub-offline-datasette-"))
    raw = root / "raw.db"
    envelope = root / "envelope.db"
    output = root / "personalhub_read.db"

    source_tables = (
        "contacts", "contact_fields", "places", "finance_accounts", "finance_transactions",
        "hub_contexts", "hub_entity_bindings", "hub_context_members",
    )
    with sqlite3.connect(raw) as db:
        for name in source_tables:
            db.execute(entity(schema, name)["createSql"].replace("${TABLE_NAME}", name))
        db.execute("INSERT INTO contacts(id,public_id,created_at,updated_at) VALUES(1,'person-1',1000,1000)")
        db.execute("INSERT INTO contact_fields(id,contact_id,field_type,value,added_at,position,is_primary) VALUES(10,1,'name','Ada Example',1000,0,1)")
        db.execute("INSERT INTO places(uuid,nickname,created_at,updated_at,archived) VALUES('place-1','Home',1000,1000,0)")
        db.execute("INSERT INTO finance_accounts(id,name,currency,openingBalance,openedAt,included) VALUES('acc','Cash','DKK','0',1000,1)")
        db.execute("INSERT INTO finance_transactions(id,accountId,uuid,amount,currency,fromReceipt,notes,occurredAt,createdAt,updatedAt) VALUES(20,'acc','tx-1','12.5','DKK',0,'',2000,2000,2000)")
        db.execute("INSERT INTO hub_contexts(id,title,created_at,updated_at) VALUES('ctx','Purchase context',3000,3000)")
        db.execute("INSERT INTO hub_entity_bindings(id,module_id,entity_kind,canonical_id,lifecycle,updated_at) VALUES('bind-place','places','place','place-1','active',3000)")
        db.execute("INSERT INTO hub_entity_bindings(id,module_id,entity_kind,canonical_id,lifecycle,updated_at) VALUES('bind-tx','soldi','transaction','tx-1','active',3000)")
        db.execute("INSERT INTO hub_context_members(context_id,entity_id,role,position) VALUES('ctx','bind-place','member',0)")
        db.execute("INSERT INTO hub_context_members(context_id,entity_id,role,position) VALUES('ctx','bind-tx','member',1)")
        db.commit()

    envelope_rows = local_envelope.build_local_envelope(raw, envelope)
    result = projection.project(envelope, output, SCHEMA, reconcile=True)
    with sqlite3.connect(output) as db:
        db.row_factory = sqlite3.Row
        field = db.execute("SELECT value,contact_id,contact_id_source_id FROM contact_fields WHERE state='active'").fetchone()
        contact = db.execute("SELECT name,record_id FROM contacts WHERE state='active'").fetchone()
        relation = db.execute("SELECT * FROM hub_entity_relations").fetchone()
        relation_targets = {row[2] for row in db.execute("PRAGMA foreign_key_list(hub_entity_relations)")}
        fk_check = list(db.execute("PRAGMA foreign_key_check"))
        assert field["contact_id"] == contact["record_id"]
        assert field["contact_id_source_id"] == 1
        assert contact["name"] == "Ada Example"
        assert relation is not None
        assert {"places", "finance_transactions"} <= relation_targets
        assert not fk_check
    return {"envelope_rows": envelope_rows, "tables": result["tables"], "relation_targets": sorted(relation_targets)}


def main() -> None:
    checked = test_manifest()
    test_no_runtime_network()
    projection = test_projection()
    print(json.dumps({"status": "PASS", "manifest_files": checked, **projection}, sort_keys=True))


if __name__ == "__main__":
    main()
