import json,sqlite3,tempfile,unittest,uuid
from contextlib import closing
from pathlib import Path
from migrate_personalhub_v23_to_v24 import migrate,rollback,mapping,validate_identities,install,ENTITIES
from migrate_personalhub_v21_to_v23 import schema,validate,snapshot
from canonical_identity_schema import statements,ASSETS,validation_queries


def fixture(path):
 db=sqlite3.connect(path);db.execute('PRAGMA foreign_keys=OFF')
 s=schema(23);entities={e['tableName']:e for e in s['entities']}
 for e in s['entities']:
  db.execute(e['createSql'].replace('${TABLE_NAME}',e['tableName']))
  for i in e.get('indices',[]):db.execute(i['createSql'].replace('${TABLE_NAME}',e['tableName']))
 db.execute('CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)');db.execute('INSERT INTO room_master_table VALUES(42,?)',(s['identityHash'],));db.execute('PRAGMA user_version=23')
 rows={}
 for t,entry in ENTITIES.items():
  e=entities[t];row={}
  for f in e['fields']:
   col=f['columnName']
   if not f.get('notNull'):row[col]=None
   elif f['affinity']=='INTEGER':row[col]=1
   elif f['affinity']=='REAL':row[col]=1.0
   elif f['affinity']=='BLOB':row[col]=b'fixture'
   else:row[col]=f'{t}-{col}'
  pk=entry['key'];row[pk]=1 if next(f for f in e['fields'] if f['columnName']==pk)['affinity']=='INTEGER' else 'stable-'+t
  if not entry['added']:row[entry['column']]='stable-'+t
  rows[t]=row
 # Referenced required local keys must exist. Optional cross-domain links exercise the migration.
 for t,row in rows.items():
  for fk in entities[t].get('foreignKeys',[]):
   for col,parent in zip(fk['columns'],fk['referencedColumns']):
    if col in row and row[col] is not None:row[col]=rows[fk['table']][parent]
 rows['contacts']['public_id']='person-existing'
 rows['finance_transactions']['personId']=1;rows['finance_recurrences']['personId']=1
 rows['prescriptions']['doctor_contact_id']=1;rows['prescriptions']['finance_transaction_id']=1
 rows['since_when_counters'].update(source_entity_type='substances/substance',source_entity_id='1')
 for t,row in rows.items():db.execute(f'INSERT INTO "{t}"({",".join(chr(34)+c+chr(34) for c in row)}) VALUES({",".join("?" for _ in row)})',tuple(row.values()))
 db.execute("INSERT INTO hub_entity_bindings VALUES('binding','substances','substance','1','ACTIVE',1)")
 db.execute("INSERT INTO hub_context_members VALUES('stable-hub_contexts','binding','',0)")
 db.commit();validate(db,23);db.close()

class IdentityMigrationTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=Path(self.tmp.name);self.source=self.root/'source.db';fixture(self.source)
 def tearDown(self):self.tmp.cleanup()
 def migrated(self):
  out=self.root/'migrated.db';report=migrate(self.source,out);return out,sqlite3.connect(out),report
 def test_shared_assets_and_classification_are_exhaustive(self):
  tables={e['tableName'] for e in schema(23)['entities']}
  contract=json.loads((ASSETS/'canonical-identities.json').read_text())
  self.assertEqual(tables,{e['table'] for e in contract['tables']})
  self.assertEqual(statements(),json.loads((ASSETS/'canonical-identity-triggers.json').read_text()))
  self.assertEqual(validation_queries(),json.loads((ASSETS/'canonical-identity-validation.json').read_text()))
 def test_backup_map_preservation_idempotence_and_exact_rollback(self):
  out,db,report=self.migrated()
  original=self.source.read_bytes();ids=mapping(db)
  self.assertEqual(35,report['mapped_rows']);self.assertEqual('person-existing',ids['contacts']['1']);self.assertEqual('stable-places',ids['places']['stable-places'])
  for t,e in ENTITIES.items():
   if e['added']:self.assertEqual(4,uuid.UUID(ids[t]['1']).version)
  self.assertEqual(ids['substances']['1'],db.execute('SELECT source_entity_id FROM since_when_counters').fetchone()[0])
  self.assertEqual('1',db.execute('SELECT legacy_source_entity_id FROM since_when_counters').fetchone()[0]);db.close()
  again=self.root/'again.db';migrate(out,again)
  with closing(sqlite3.connect(again)) as c, c:self.assertEqual(ids,mapping(c));validate_identities(c)
  restored=self.root/'restored.db';rollback(out,restored)
  with closing(sqlite3.connect(self.source)) as a, closing(sqlite3.connect(restored)) as b:self.assertEqual(snapshot(a),snapshot(b));validate(b,23)
  self.assertEqual(original,self.source.read_bytes())
 def test_uniqueness_not_null_immutability_tombstone_and_legacy_constructors(self):
  out,db,report=self.migrated();cid=mapping(db)['substances']['1']
  with self.assertRaises(sqlite3.IntegrityError):db.execute('UPDATE substances SET canonical_id=? WHERE id=1',('changed',))
  with self.assertRaises(sqlite3.IntegrityError):db.execute('UPDATE contacts SET public_id=NULL WHERE id=1')
  with self.assertRaises(sqlite3.IntegrityError):db.execute("INSERT INTO finance_accounts VALUES('person-existing','x','EUR','0',0,1)")
  db.execute("UPDATE substances SET canonical_id='',name='renamed' WHERE id=1")
  self.assertEqual(cid,mapping(db)['substances']['1'])
  db.execute('DELETE FROM quick_event_entries WHERE id=1')
  self.assertEqual('TOMBSTONED',db.execute("SELECT lifecycle FROM hub_entities WHERE local_table='quick_event_entries'").fetchone()[0])
  with self.assertRaises(sqlite3.IntegrityError):db.execute("DELETE FROM hub_entities WHERE local_table='quick_event_entries'")
  validate_identities(db);db.close()
 def test_external_uniqueness_kind_and_orphans(self):
  _,db,_=self.migrated();sql="INSERT INTO hub_external_identities(id,canonical_id,entity_kind,system,source_scope,external_id,link_method,lifecycle,created_at,updated_at) VALUES(?,?,?,?,?,?,'explicit','ACTIVE',1,1)"
  db.execute(sql,('external-1','person-existing','people/person','provider','account','native-1'))
  for row in [('external-2','person-existing','people/person','provider','account','native-1'),('external-3','missing','people/person','provider','account','native-2'),('external-4','person-existing','wrong/kind','provider','account','native-3')]:
   with self.assertRaises(sqlite3.IntegrityError):db.execute(sql,row)
  with self.assertRaises(sqlite3.IntegrityError):db.execute("UPDATE hub_external_identities SET external_id='changed'")
  db.execute("UPDATE hub_external_identities SET lifecycle='RETIRED'");validate_identities(db);db.close()
 def test_alias_cycle_permanence_and_deterministic_resolution(self):
  _,db,_=self.migrated();a='person-existing'
  db.execute("INSERT INTO contacts(id,public_id,created_at,updated_at) VALUES(2,'p2',1,1)")
  db.execute("INSERT INTO hub_entity_aliases VALUES(?,?,NULL,1)",(a,'p2'))
  with self.assertRaises(sqlite3.IntegrityError):db.execute("INSERT INTO hub_entity_aliases VALUES(?,?,NULL,1)",('p2',a))
  with self.assertRaises(sqlite3.IntegrityError):db.execute("UPDATE hub_entity_aliases SET canonical_id='missing'")
  with self.assertRaises(sqlite3.IntegrityError):db.execute('DELETE FROM hub_entity_aliases')
  self.assertEqual('MERGED',db.execute('SELECT lifecycle FROM hub_entities WHERE canonical_id=?',(a,)).fetchone()[0]);validate_identities(db);db.close()
 def test_both_directions_of_cross_domain_dual_write_and_collision(self):
  _,db,_=self.migrated()
  db.execute("INSERT INTO contacts(id,public_id,created_at,updated_at) VALUES(2,'person-2',1,1)")
  db.execute("UPDATE finance_transactions SET personId=2 WHERE id=1")
  self.assertEqual((2,'person-2'),db.execute('SELECT personId,person_canonical_id FROM finance_transactions').fetchone())
  db.execute("UPDATE finance_transactions SET person_canonical_id='person-existing' WHERE id=1")
  self.assertEqual((1,'person-existing'),db.execute('SELECT personId,person_canonical_id FROM finance_transactions').fetchone())
  db.execute("INSERT INTO contacts(id,public_id,created_at,updated_at) VALUES(3,'person-3',1,1)")
  with self.assertRaises(sqlite3.IntegrityError):db.execute("UPDATE finance_transactions SET personId=2,person_canonical_id='person-3' WHERE id=1")
  with self.assertRaises(sqlite3.IntegrityError):db.execute("UPDATE finance_recurrences SET personId=999")
  with self.assertRaises(sqlite3.IntegrityError):db.execute("DELETE FROM contacts WHERE id=1")
  validate_identities(db);db.close()
 def test_global_collision_and_orphan_fail_without_publishing_or_mutating(self):
  original=self.source.read_bytes()
  with closing(sqlite3.connect(self.source)) as c, c:c.execute("UPDATE saved_searches SET public_id='person-existing'")
  collision=self.source.read_bytes()
  with self.assertRaises(ValueError):migrate(self.source,self.root/'bad.db')
  self.assertFalse((self.root/'bad.db').exists());self.assertEqual(collision,self.source.read_bytes())
 def test_qa_overlay_requires_explicit_classification(self):
  with closing(sqlite3.connect(self.source)) as c, c:c.execute("UPDATE contacts SET public_id='qa-overlay-fixture'")
  with self.assertRaises(ValueError):migrate(self.source,self.root/'bad.db')
  report=migrate(self.source,self.root/'classified.db','preserve');self.assertEqual('PASS',report['status'])
 def test_snapshot_alert_identity_coverage_and_replay(self):
  with closing(sqlite3.connect(self.source)) as c,c:
   c.execute('INSERT INTO snapshot(id,json,saved_at_ms) VALUES(1,?,1)',(json.dumps({'timeFenceRules':[{'id':42,'isDeleted':False},{'id':43,'isDeleted':True}]}),))
  out,db,report=self.migrated();ids=mapping(db)['snapshot.timeFenceRules']
  self.assertEqual({'42','43'},set(ids));self.assertTrue(all(uuid.UUID(cid).version==4 for cid in ids.values()))
  self.assertEqual('TOMBSTONED',db.execute('SELECT lifecycle FROM hub_entities WHERE canonical_id=?',(ids['43'],)).fetchone()[0]);db.close()
  again=self.root/'again.db';migrate(out,again)
  with closing(sqlite3.connect(again)) as c,c:
   self.assertEqual(ids,mapping(c)['snapshot.timeFenceRules'])
   c.execute('DROP TRIGGER canonical_registry_delete');c.execute('DELETE FROM hub_entities WHERE canonical_id=?',(ids['42'],))
   with self.assertRaises(ValueError):validate_identities(c)
 def test_kind_mismatches_and_alias_orphans_fail_even_with_fk_disabled(self):
  _,db,_=self.migrated()
  with self.assertRaises(sqlite3.IntegrityError):db.execute("INSERT INTO hub_entity_bindings VALUES('wrong','places','place','person-existing','ACTIVE',1)")
  with self.assertRaises(sqlite3.IntegrityError):db.execute("INSERT INTO hub_entity_aliases VALUES('missing','person-existing',NULL,1)")
  db.close()
 def test_wal_backup_includes_committed_writes_and_rollback_hash_gate(self):
  with closing(sqlite3.connect(self.source)) as c, c:
   c.execute('PRAGMA journal_mode=WAL');c.execute("UPDATE contacts SET public_id='wal-person'");c.commit()
   out,db,_=self.migrated();self.assertEqual('wal-person',mapping(db)['contacts']['1']);db.close()
  backup=Path(str(out)+'.backup-v23.db');backup.write_bytes(b'tampered')
  with self.assertRaises(ValueError):rollback(out,self.root/'bad-restore.db')

if __name__=='__main__':unittest.main()
