#!/usr/bin/env python3
"""Prepare an isolated v24 identity migration; never modify the source or any device."""
from __future__ import annotations
import argparse, hashlib, json, os, sqlite3, tempfile, time, uuid
from pathlib import Path
from contextlib import closing
from canonical_identity_schema import ROOT, ASSETS, CONTRACT, ENTITIES, REFERENCES, statements, UUID, validation_queries
from migrate_personalhub_v21_to_v23 import schema, validate as validate_room, check, digest

REGISTRIES = {'hub_entities','hub_external_identities','hub_entity_aliases'}

def validate_identities(db):
    for query in validation_queries():
        if db.execute(query['sql'],query['args']).fetchone():raise ValueError(query['error'])
    for t,e in ENTITIES.items():
        key,col=e['key'],e['column']
        bad=db.execute(f'''SELECT 1 FROM "{t}" r LEFT JOIN hub_entities e ON e.canonical_id=r."{col}"
         WHERE r."{col}" IS NULL OR length(trim(r."{col}"))=0 OR e.canonical_id IS NULL
          OR e.entity_kind<>? OR e.local_table<>? OR e.local_key<>CAST(r."{key}" AS TEXT) LIMIT 1''',(e['kind'],t)).fetchone()
        if bad:raise ValueError(f'canonical coverage/integrity failed: {t}')
    for t,local,canonical,target in REFERENCES:
        e=ENTITIES[target]
        if db.execute(f'''SELECT 1 FROM "{t}" r LEFT JOIN "{target}" p ON p."{e['key']}"=r."{local}"
         WHERE r."{canonical}" IS NOT p."{e['column']}" OR (r."{local}" IS NOT NULL AND p."{e['key']}" IS NULL) LIMIT 1''').fetchone():raise ValueError(f'dual reference/orphan: {t}.{canonical}')
    if db.execute("SELECT 1 FROM hub_entity_bindings b LEFT JOIN hub_entities e ON e.canonical_id=b.canonical_id WHERE e.canonical_id IS NULL LIMIT 1").fetchone():raise ValueError('orphan binding')
    if db.execute("SELECT 1 FROM hub_external_identities x LEFT JOIN hub_entities e ON e.canonical_id=x.canonical_id AND e.entity_kind=x.entity_kind WHERE e.canonical_id IS NULL LIMIT 1").fetchone():raise ValueError('orphan external identity')
    if db.execute('''SELECT 1 FROM since_when_counters s LEFT JOIN hub_entities e ON e.canonical_id=s.source_entity_id AND e.entity_kind=s.source_entity_type
     WHERE (s.source_entity_type IS NULL)<>(s.source_entity_id IS NULL) OR (s.source_entity_id IS NOT NULL AND e.canonical_id IS NULL) LIMIT 1''').fetchone():raise ValueError('orphan Since When source')
    if db.execute('''SELECT 1 FROM hub_context_members m LEFT JOIN hub_entity_bindings b ON b.id=m.entity_id
     WHERE m.entity_canonical_id IS NOT b.canonical_id LIMIT 1''').fetchone():raise ValueError('orphan context entity')
    aliases=dict(db.execute('SELECT alias_canonical_id,canonical_id FROM hub_entity_aliases'))
    for source in aliases:
        current=source;seen=set()
        while current in aliases:
            if current in seen:raise ValueError('alias cycle')
            seen.add(current);current=aliases[current]
        if not db.execute('SELECT 1 FROM hub_entities WHERE canonical_id=?',(current,)).fetchone():raise ValueError('orphan alias')
    check(db)


def mapping(db):
    result = {t:{str(key):cid for key,cid in db.execute(f'SELECT "{e["key"]}","{e["column"]}" FROM "{t}" ORDER BY "{e["key"]}"')} for t,e in ENTITIES.items()}
    for e in CONTRACT.get('virtual_entities',[]):
        result[e['table']] = dict(db.execute('SELECT local_key,canonical_id FROM hub_entities WHERE local_table=? ORDER BY local_key',(e['table'],)))
    return result


def install(db):
    for sql in statements():db.execute(sql)


def rebuild(db, source_schema, target_schema):
    old={e['tableName']:e for e in source_schema['entities']}
    for e in target_schema['entities']:
        t=e['tableName'];before=old.get(t)
        if before is not None and e['createSql']==before['createSql'] and e.get('indices')==before.get('indices'):continue
        tmp='__identity_upgrade_'+t
        db.execute(e['createSql'].replace('${TABLE_NAME}',tmp))
        if before:
            columns=[f['columnName'] for f in before['fields']]
            expressions=[]
            for col in columns:
                if col=='public_id' and t=='contacts':expressions.append(f'COALESCE(NULLIF("{col}",\'\'),{UUID})')
                else:expressions.append('"'+col+'"')
            if t in ENTITIES and ENTITIES[t]['added']:
                columns.append('canonical_id');expressions.append(UUID)
            db.execute(f'INSERT INTO "{tmp}" ({",".join(chr(34)+c+chr(34) for c in columns)}) SELECT {",".join(expressions)} FROM "{t}"')
            db.execute(f'DROP TABLE "{t}"')
        db.execute(f'ALTER TABLE "{tmp}" RENAME TO "{t}"')
        for idx in e.get('indices',[]):db.execute(idx['createSql'].replace('${TABLE_NAME}',t))


def backfill(db, qa_overlay_decision):
    # Do not infer whether a QA-labelled person is production data.
    overlays=db.execute("SELECT count(*) FROM contacts WHERE public_id LIKE 'qa-overlay-%'").fetchone()[0]
    if overlays and qa_overlay_decision!='preserve':raise ValueError('qa-overlay person requires explicit --qa-overlay-decision preserve after classification')
    stamp=int(time.time()*1000)
    for t,e in ENTITIES.items():
        key,col=e['key'],e['column']
        for old,cid in db.execute(f'SELECT "{key}","{col}" FROM "{t}"').fetchall():
            if cid is None or str(cid).strip()=='':
                if not e['added'] and t not in ['contacts','finance_products']:raise ValueError(f'missing established ID: {t}')
                cid=str(uuid.uuid4());db.execute(f'UPDATE "{t}" SET "{col}"=? WHERE "{key}"=?',(cid,old))
            tomb=e.get('tombstone_column')
            life='ACTIVE'
            if tomb and db.execute(f'SELECT "{tomb}" IS NOT NULL FROM "{t}" WHERE "{key}"=?',(old,)).fetchone()[0]:life='TOMBSTONED'
            try:db.execute('INSERT INTO hub_entities VALUES(?,?,?,?,?,?,?,?)',(cid,e['kind'],e['module'],t,str(old),life,stamp,stamp))
            except sqlite3.IntegrityError as err:raise ValueError(f'global canonical collision in {t}') from err
    for virtual in CONTRACT.get('virtual_entities',[]):
        value=db.execute('SELECT json FROM snapshot WHERE id=1').fetchone()
        rules=json.loads(value[0]).get(virtual['json_array'],[]) if value else []
        seen=set()
        for rule in rules:
            key=str(rule['id'])
            if key in seen:raise ValueError('duplicate Timer alert local ID')
            seen.add(key)
            db.execute('INSERT INTO hub_entities VALUES(?,?,?,?,?,?,?,?)',(str(uuid.uuid4()),virtual['kind'],virtual['module'],virtual['table'],key,'TOMBSTONED' if rule.get('isDeleted') else 'ACTIVE',stamp,stamp))
    for t,local,canonical,target in REFERENCES:
        e=ENTITIES[target]
        db.execute(f'UPDATE "{t}" SET "{canonical}"=(SELECT "{e["column"]}" FROM "{target}" WHERE "{e["key"]}"="{t}"."{local}")')
    # Only explicit kind/local mappings are used. No name/timestamp/heuristic match.
    for table,kindcol,idcol in [('hub_entity_bindings','entity_kind','canonical_id'),('since_when_counters','source_entity_type','source_entity_id')]:
        rows=db.execute(f'SELECT rowid,"{kindcol}","{idcol}"'+(',module_id' if table=='hub_entity_bindings' else '')+f' FROM "{table}"').fetchall()
        for row in rows:
            rowid,kind,identifier=row[:3]
            if kind is None and identifier is None:continue
            full_kind=row[3]+'/'+kind if table=='hub_entity_bindings' else kind
            if kind=='alert' and table=='hub_entity_bindings' and row[3]!='timer':full_kind='alerts/alert'
            targets=[e for e in list(ENTITIES.values())+CONTRACT.get('virtual_entities',[]) if e['kind']==full_kind]
            if len(targets)!=1:raise ValueError(f'unclassified reference kind: {full_kind}')
            e=targets[0];candidates={x[0] for x in db.execute('SELECT canonical_id FROM hub_entities WHERE entity_kind=? AND (canonical_id=? OR local_key=?)',(full_kind,identifier,str(identifier)))}
            if len(candidates)!=1:raise ValueError(f'unknown/ambiguous reference in {table}')
            cid=candidates.pop()
            if table=='since_when_counters':db.execute('UPDATE since_when_counters SET legacy_source_entity_id=source_entity_id,source_entity_id=? WHERE rowid=?',(cid,rowid))
            else:db.execute('UPDATE hub_entity_bindings SET canonical_id=? WHERE rowid=?',(cid,rowid))
    db.execute('UPDATE hub_context_members SET entity_canonical_id=(SELECT canonical_id FROM hub_entity_bindings WHERE id=entity_id)')


def projection_digest(db, table, columns):
    h=hashlib.sha256();count=0
    for row in db.execute(f'SELECT {",".join(chr(34)+c+chr(34) for c in columns)} FROM "{table}" ORDER BY rowid'):
        h.update(repr(row).encode('utf-8','backslashreplace'));count+=1
    return count,h.hexdigest()


def migrate(source:Path, output:Path, qa_overlay_decision=None):
    source,output=source.resolve(),output.resolve();backup=Path(str(output)+'.backup-v23.db')
    artifacts=[output,backup,Path(str(output)+'.identity-map.json'),Path(str(output)+'.validation.json')]
    if not source.is_file() or any(p.exists() for p in artifacts) or source in artifacts:raise ValueError('source missing or destination artifact exists')
    output.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.ph-identities-',dir=output.parent) as name:
        stage=Path(name)/'target.db';frozen=Path(name)/'backup.db'
        with closing(sqlite3.connect(f'file:{source}?mode=ro',uri=True)) as src,closing(sqlite3.connect(frozen)) as snap:src.backup(snap)
        snap=sqlite3.connect(frozen)
        try:
            version=snap.execute('PRAGMA user_version').fetchone()[0]
            if version not in (23,24):raise ValueError('source must be schema 23 or 24')
            validate_room(snap,version)
            if version==24:validate_identities(snap)
            dst=sqlite3.connect(stage)
            try:
                snap.backup(dst);dst.execute('PRAGMA journal_mode=DELETE');dst.execute('PRAGMA foreign_keys=OFF');dst.execute('PRAGMA legacy_alter_table=ON')
                if version==23:
                    triggers=dst.execute("SELECT name FROM sqlite_master WHERE type='trigger'").fetchall()
                    for (t,) in triggers:dst.execute('DROP TRIGGER "'+t.replace('"','""')+'"')
                    dst.execute('BEGIN IMMEDIATE')
                    rebuild(dst,schema(23),schema(24));backfill(dst,qa_overlay_decision)
                    dst.execute('UPDATE room_master_table SET identity_hash=? WHERE id=42',(schema(24)['identityHash'],))
                    dst.execute('PRAGMA user_version=24');install(dst);dst.commit()
                    # Every original value except explicitly migrated identity/reference cells must match.
                    allowed={'contacts':{'public_id'},'finance_products':{'uuid'},'hub_entity_bindings':{'canonical_id'},'since_when_counters':{'source_entity_id'}}
                    for e in schema(23)['entities']:
                        cols=[f['columnName'] for f in e['fields'] if f['columnName'] not in allowed.get(e['tableName'],set())]
                        if projection_digest(snap,e['tableName'],cols)!=projection_digest(dst,e['tableName'],cols):raise ValueError('legacy data changed: '+e['tableName'])
                    # Preserve existing stable IDs byte for byte, including non-UUID opaque strings.
                    for t,e in ENTITIES.items():
                        if e['added']:continue
                        for key,cid in snap.execute(f'SELECT "{e["key"]}","{e["column"]}" FROM "{t}"'):
                            if cid is not None and str(cid).strip() and dst.execute(f'SELECT "{e["column"]}" FROM "{t}" WHERE "{e["key"]}"=?',(key,)).fetchone()!=(cid,):raise ValueError('established ID changed: '+t)
                else:install(dst);dst.commit()
                dst.execute('PRAGMA foreign_keys=ON');validate_room(dst,24);validate_identities(dst)
                idmap=mapping(dst)
                report={'status':'PASS','source_version':version,'target_version':24,'source_snapshot_sha256':digest(frozen),'output_sha256':digest(stage),'canonical_tables':len(ENTITIES),'classified_tables':len(CONTRACT['tables']),'mapped_rows':sum(map(len,idmap.values())),'registry_coverage':'100%','quick_check':'ok','integrity_check':'ok','foreign_key_check':[],'qa_overlay_decision':qa_overlay_decision,'rollback_backup':str(backup),'pixel_cutover':False}
            finally:dst.close()
        finally:snap.close()
        # No partially validated database is ever published. Source and its WAL are untouched.
        os.chmod(stage,0o600);os.chmod(frozen,0o600);os.replace(frozen,backup);os.replace(stage,output)
        for path,payload in [(artifacts[2],{'schema':'personalhub.canonical-map.v1','tables':idmap}),(artifacts[3],report)]:
            path.write_text(json.dumps(payload,indent=2,sort_keys=True)+'\n');os.chmod(path,0o600)
        return report


def rollback(migrated:Path, output:Path):
    report=json.loads(Path(str(migrated)+'.validation.json').read_text());backup=Path(report['rollback_backup'])
    if digest(backup)!=report['source_snapshot_sha256']:raise ValueError('rollback snapshot hash mismatch')
    if output.exists() or output.resolve() in [migrated.resolve(),backup.resolve()]:raise ValueError('rollback destination exists or overlaps source')
    with closing(sqlite3.connect(f'file:{backup.resolve()}?mode=ro',uri=True)) as src:
        validate_room(src,report['source_version'])
        with closing(sqlite3.connect(output)) as dst:src.backup(dst);validate_room(dst,report['source_version'])
    os.chmod(output,0o600)
    return {'status':'PASS','restored_version':report['source_version'],'snapshot_sha256':digest(backup),'output_sha256':digest(output)}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('source',type=Path);p.add_argument('--output',type=Path,required=True);p.add_argument('--rollback',action='store_true');p.add_argument('--qa-overlay-decision',choices=['preserve']);a=p.parse_args()
    try:print(json.dumps(rollback(a.source,a.output) if a.rollback else migrate(a.source,a.output,a.qa_overlay_decision),sort_keys=True))
    except Exception as e:print(json.dumps({'status':'FAIL','error':str(e)}));raise SystemExit(2)
