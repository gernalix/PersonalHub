"""Build the shared Android/external-runner identity SQL from the table contract."""
from pathlib import Path
import json
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'core/database/src/main/assets'
CONTRACT = json.loads((ASSETS / 'canonical-identities.json').read_text())
ENTITIES = {x['table']: x for x in CONTRACT['tables'] if x['classification'] == 'canonical'}
NOW = "CAST((julianday('now')-2440587.5)*86400000 AS INTEGER)"
UUID = "lower(hex(randomblob(4))||'-'||hex(randomblob(2))||'-4'||substr(hex(randomblob(2)),2)||'-'||substr('89ab',1+abs(random()%4),1)||substr(hex(randomblob(2)),2)||'-'||hex(randomblob(6)))"
# Legacy keys retained solely inside the database; both directions are maintained atomically.
REFERENCES = [('finance_transactions','personId','person_canonical_id','contacts'),
 ('finance_recurrences','personId','person_canonical_id','contacts'),
 ('prescriptions','doctor_contact_id','doctor_canonical_id','contacts'),
 ('prescriptions','finance_transaction_id','transaction_canonical_id','finance_transactions')]

def trigger(name, timing, operation, table, body, when=''):
 return f'CREATE TRIGGER IF NOT EXISTS `{name}` {timing} {operation} ON `{table}`'+(f' WHEN {when}' if when else '')+f' BEGIN {body} END'

def fail_if(condition, reason):
 return f"SELECT RAISE(ABORT,'{reason}') WHERE {condition}; "

def statements():
 result=[]
 for t,e in ENTITIES.items():
  key,col=e['key'],e['column'];kind,module=e['kind'],e['module']
  def lookup(prefix):return f"(SELECT canonical_id FROM hub_entities WHERE local_table='{t}' AND local_key=CAST({prefix}.`{key}` AS TEXT) AND owning_module='{module}')"
  def life(prefix):
   deleted=e.get('tombstone_column')
   return f"CASE WHEN {prefix}.`{deleted}` IS NOT NULL THEN 'TOMBSTONED' ELSE 'ACTIVE' END" if deleted else "'ACTIVE'"
  identity=f'NEW.`{col}`'
  # The empty added-column default is a compatibility request, never a persisted identity.
  # UPDATEs from legacy row constructors retain the previous ID; explicit nonempty reassignment fails.
  if e['added']:
   result.append(trigger(f'canonical_{t}_retain','AFTER','UPDATE',t,
    f'UPDATE `{t}` SET `{col}`=OLD.`{col}` WHERE `{key}`=NEW.`{key}`;',f"NEW.`{col}`='' AND OLD.`{col}`<>''"))
  guard=fail_if(f"NEW.`{key}` IS NOT OLD.`{key}`",'immutable local identity key')
  allow_blank=f" AND NEW.`{col}`<>''" if e['added'] else ''
  guard+=fail_if(f"OLD.`{col}`<>'' AND NEW.`{col}` IS NOT OLD.`{col}`{allow_blank}",'immutable canonical identity')
  result.append(trigger(f'canonical_{t}_immutable','BEFORE','UPDATE',t,guard))
  insert_body=''
  if e['added']:
   insert_body+=f"UPDATE `{t}` SET `{col}`=COALESCE({lookup('NEW')},{UUID}) WHERE `{key}`=NEW.`{key}` AND `{col}`=''; "
   identity=f'(SELECT `{col}` FROM `{t}` WHERE `{key}`=NEW.`{key}`)'
  insert_body+=fail_if(f"{identity} IS NULL OR length(trim({identity}))=0",'empty canonical identity')
  insert_body+=fail_if(f"EXISTS(SELECT 1 FROM hub_entities WHERE canonical_id={identity} AND (entity_kind<>'{kind}' OR local_table<>'{t}' OR local_key<>CAST(NEW.`{key}` AS TEXT)))",'canonical identity collision')
  # REPLACE/compatibility writes can retain a live existing mapping. Reuse after actual deletion fails.
  insert_body+=fail_if(f"EXISTS(SELECT 1 FROM hub_entities WHERE canonical_id={identity} AND (lifecycle='MERGED' OR (lifecycle='TOMBSTONED' AND (NEW.`{col}` IS NULL OR NEW.`{col}`=''))))",'canonical identity cannot be reused')
  insert_body+=f"INSERT INTO hub_entities(canonical_id,entity_kind,owning_module,local_table,local_key,lifecycle,created_at,updated_at) SELECT {identity},'{kind}','{module}','{t}',CAST(NEW.`{key}` AS TEXT),{life('NEW')},{NOW},{NOW} WHERE NOT EXISTS(SELECT 1 FROM hub_entities WHERE canonical_id={identity}); "
  insert_body+=f"UPDATE hub_entities SET lifecycle={life('NEW')},updated_at={NOW} WHERE canonical_id={identity} AND lifecycle='TOMBSTONED'; "
  result.append(trigger(f'canonical_{t}_insert','AFTER','INSERT',t,insert_body))
  result.append(trigger(f'canonical_{t}_update','AFTER','UPDATE',t,
    f"UPDATE hub_entities SET lifecycle={life('NEW')},updated_at={NOW} WHERE canonical_id=NEW.`{col}` AND lifecycle<>'MERGED';",f"NEW.`{col}`<>'' AND {life('NEW')} IS NOT {life('OLD')}"))
  result.append(trigger(f'canonical_{t}_delete','AFTER','DELETE',t,
    f"UPDATE hub_entities SET lifecycle='TOMBSTONED',updated_at={NOW} WHERE canonical_id=OLD.`{col}` AND lifecycle<>'MERGED';"))
 # Registry is permanent. Table-level CHECK equivalents are triggers because Room cannot declare CHECK.
 registry_checks=" OR ".join(f"NEW.{c} IS NULL OR length(trim(NEW.{c}))=0" for c in ['canonical_id','entity_kind','owning_module','local_table','local_key'])+" OR NEW.lifecycle NOT IN ('ACTIVE','TOMBSTONED','MERGED')"
 for op in ['INSERT','UPDATE']:
  body=fail_if(registry_checks,'invalid canonical registry record')
  if op=='UPDATE':body+=fail_if(' OR '.join(f'NEW.{c} IS NOT OLD.{c}' for c in ['canonical_id','entity_kind','owning_module','local_table','local_key','created_at']),'immutable registry identity')
  result.append(trigger(f'canonical_registry_{op}','BEFORE',op,'hub_entities',body))
 result.append(trigger('canonical_registry_delete','BEFORE','DELETE','hub_entities',"SELECT RAISE(ABORT,'canonical registry is permanent');"))
 for op in ['INSERT','UPDATE']:
  bad="NEW.lifecycle NOT IN ('ACTIVE','RETIRED') OR length(trim(NEW.id))=0 OR length(trim(NEW.system))=0 OR length(trim(NEW.source_scope))=0 OR length(trim(NEW.external_id))=0 OR length(trim(NEW.link_method))=0 OR NOT EXISTS(SELECT 1 FROM hub_entities WHERE canonical_id=NEW.canonical_id AND entity_kind=NEW.entity_kind)"
  body=fail_if(bad,'invalid external identity or orphan')
  if op=='UPDATE':body+=fail_if(' OR '.join(f'NEW.{c} IS NOT OLD.{c}' for c in ['id','canonical_id','entity_kind','system','source_scope','external_id','created_at']),'immutable external identity tuple')
  result.append(trigger(f'canonical_external_{op}','BEFORE',op,'hub_external_identities',body))
 result.append(trigger('canonical_external_delete','BEFORE','DELETE','hub_external_identities',"SELECT RAISE(ABORT,'retire external identity instead of deleting');"))
 cycle="NEW.alias_canonical_id IN (WITH RECURSIVE path(id) AS (SELECT NEW.canonical_id UNION SELECT a.canonical_id FROM hub_entity_aliases a JOIN path p ON a.alias_canonical_id=p.id) SELECT id FROM path)"
 body=fail_if("length(trim(NEW.alias_canonical_id))=0 OR NOT EXISTS(SELECT 1 FROM hub_entities WHERE canonical_id=NEW.alias_canonical_id) OR NOT EXISTS(SELECT 1 FROM hub_entities WHERE canonical_id=NEW.canonical_id)",'invalid alias target')
 body+=fail_if(cycle,'canonical alias cycle')
 body+=fail_if("EXISTS(SELECT 1 FROM hub_entities a JOIN hub_entities b ON b.canonical_id=NEW.canonical_id WHERE a.canonical_id=NEW.alias_canonical_id AND a.entity_kind<>b.entity_kind)",'alias kind mismatch')
 result.append(trigger('canonical_alias_insert','BEFORE','INSERT','hub_entity_aliases',body))
 result.append(trigger('canonical_alias_lifecycle','AFTER','INSERT','hub_entity_aliases',f"UPDATE hub_entities SET lifecycle='MERGED',updated_at={NOW} WHERE canonical_id=NEW.alias_canonical_id;"))
 for op in ['UPDATE','DELETE']:result.append(trigger(f'canonical_alias_{op}','BEFORE',op,'hub_entity_aliases',"SELECT RAISE(ABORT,'canonical aliases are permanent');"))
 # No unknown entity can enter the binding layer.
 for op in ['INSERT','UPDATE']:
  result.append(trigger(f'canonical_binding_{op}','BEFORE',op,'hub_entity_bindings',fail_if("NOT EXISTS(SELECT 1 FROM hub_entities WHERE canonical_id=NEW.canonical_id AND entity_kind=CASE WHEN NEW.entity_kind='alert' AND NEW.module_id<>'timer' THEN 'alerts/alert' ELSE NEW.module_id||'/'||NEW.entity_kind END)",'orphan canonical binding')))
 for t,legacy,canon,target in REFERENCES:
  e=ENTITIES[target];pk,col=e['key'],e['column']
  by_local=f'(SELECT `{col}` FROM `{target}` WHERE `{pk}`=NEW.`{legacy}`)'
  by_canon=f'(SELECT `{pk}` FROM `{target}` WHERE `{col}`=NEW.`{canon}`)'
  for op in ['INSERT','UPDATE']:
   legacy_changed='1' if op=='INSERT' else f'NEW.`{legacy}` IS NOT OLD.`{legacy}`'
   canon_changed='1' if op=='INSERT' else f'NEW.`{canon}` IS NOT OLD.`{canon}`'
   invalid=fail_if(f'({legacy_changed}) AND NEW.`{legacy}` IS NOT NULL AND {by_local} IS NULL','orphan legacy reference')
   invalid+=fail_if(f'({canon_changed}) AND NEW.`{canon}` IS NOT NULL AND {by_canon} IS NULL','orphan canonical reference')
   invalid+=fail_if(f'({legacy_changed}) AND ({canon_changed}) AND NEW.`{legacy}` IS NOT NULL AND NEW.`{canon}` IS NOT NULL AND {by_local} IS NOT NEW.`{canon}`','ambiguous dual reference')
   result.append(trigger(f'canonical_{t}_{canon}_{op}_guard','BEFORE',op,t,invalid))
   if op=='INSERT':value=f'CASE WHEN NEW.`{canon}` IS NOT NULL THEN NEW.`{canon}` ELSE {by_local} END';local=f'CASE WHEN NEW.`{canon}` IS NOT NULL THEN {by_canon} ELSE NEW.`{legacy}` END'
   else:value=f'CASE WHEN {canon_changed} THEN NEW.`{canon}` ELSE {by_local} END';local=f'CASE WHEN {canon_changed} THEN {by_canon} ELSE NEW.`{legacy}` END'
   result.append(trigger(f'canonical_{t}_{canon}_{op}','AFTER',op,t,f'UPDATE `{t}` SET `{canon}`={value},`{legacy}`={local} WHERE `{ENTITIES[t]["key"]}`=NEW.`{ENTITIES[t]["key"]}`;',f'NEW.`{canon}` IS NOT {value} OR NEW.`{legacy}` IS NOT {local}'))
 # References prevent hard deletion of cross-domain parents while legacy links still need their PK.
 for target in sorted({r[3] for r in REFERENCES}):
  e=ENTITIES[target]
  checks=' OR '.join(f'EXISTS(SELECT 1 FROM `{t}` WHERE `{canon}`=OLD.`{e["column"]}`)' for t,_,canon,p in REFERENCES if p==target)
  result.append(trigger(f'canonical_{target}_referenced','BEFORE','DELETE',target,fail_if(checks,'canonical parent is referenced')))
 for op in ['INSERT','UPDATE']:
  result.append(trigger(f'canonical_member_{op}','AFTER',op,'hub_context_members',
   'UPDATE hub_context_members SET entity_canonical_id=(SELECT canonical_id FROM hub_entity_bindings WHERE id=NEW.entity_id) WHERE context_id=NEW.context_id AND entity_id=NEW.entity_id AND role=NEW.role;',
   'NEW.entity_canonical_id IS NOT (SELECT canonical_id FROM hub_entity_bindings WHERE id=NEW.entity_id)'))
  result.append(trigger(f'canonical_source_{op}','BEFORE',op,'since_when_counters',fail_if("(NEW.source_entity_type IS NULL)<>(NEW.source_entity_id IS NULL) OR (NEW.source_entity_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM hub_entities WHERE canonical_id=NEW.source_entity_id AND entity_kind=NEW.source_entity_type))",'orphan Since When source')))
 return result

def validation_queries():
 queries=[]
 def add(sql,error,args=()):queries.append(dict(sql=sql,args=list(args),error=error))
 for t,e in ENTITIES.items():
  col,key=e['column'],e['key']
  add(f"SELECT 1 FROM `{t}` r LEFT JOIN hub_entities e ON e.canonical_id=r.`{col}` WHERE r.`{col}` IS NULL OR length(trim(r.`{col}`))=0 OR e.canonical_id IS NULL OR e.local_table<>? OR e.local_key<>CAST(r.`{key}` AS TEXT) OR e.entity_kind<>? LIMIT 1",'Canonical registry coverage failed: '+t,(t,e['kind']))
 for t,e in ENTITIES.items():
  add(f"SELECT 1 FROM hub_entities e LEFT JOIN `{t}` r ON e.canonical_id=r.`{e['column']}` AND e.local_key=CAST(r.`{e['key']}` AS TEXT) WHERE e.local_table='{t}' AND e.lifecycle='ACTIVE' AND r.`{e['key']}` IS NULL LIMIT 1", 'Orphan active registry entity: '+t)
 for t,legacy,canon,target in REFERENCES:
  e=ENTITIES[target]
  add(f"SELECT 1 FROM `{t}` r LEFT JOIN `{target}` p ON p.`{e['key']}`=r.`{legacy}` WHERE r.`{canon}` IS NOT p.`{e['column']}` OR (r.`{legacy}` IS NOT NULL AND p.`{e['key']}` IS NULL) LIMIT 1",'Orphan dual reference: '+t)
 add("SELECT 1 FROM hub_entity_bindings b LEFT JOIN hub_entities e ON e.canonical_id=b.canonical_id AND e.entity_kind=CASE WHEN b.entity_kind='alert' AND b.module_id<>'timer' THEN 'alerts/alert' ELSE b.module_id||'/'||b.entity_kind END WHERE e.canonical_id IS NULL LIMIT 1",'Orphan canonical binding')
 add("SELECT 1 FROM hub_external_identities x LEFT JOIN hub_entities e ON e.canonical_id=x.canonical_id AND e.entity_kind=x.entity_kind WHERE e.canonical_id IS NULL LIMIT 1",'Orphan external identity')
 add("SELECT 1 FROM hub_context_members m LEFT JOIN hub_entity_bindings b ON b.id=m.entity_id WHERE m.entity_canonical_id IS NOT b.canonical_id LIMIT 1",'Orphan context entity')
 add("SELECT 1 FROM since_when_counters s LEFT JOIN hub_entities e ON e.canonical_id=s.source_entity_id AND e.entity_kind=s.source_entity_type WHERE (s.source_entity_type IS NULL)<>(s.source_entity_id IS NULL) OR (s.source_entity_id IS NOT NULL AND e.canonical_id IS NULL) LIMIT 1",'Orphan Since When source')
 add("SELECT 1 FROM hub_entities WHERE lifecycle NOT IN ('ACTIVE','TOMBSTONED','MERGED') OR length(trim(canonical_id))=0 OR length(trim(entity_kind))=0 LIMIT 1",'Invalid registry lifecycle')
 add("SELECT 1 FROM hub_external_identities WHERE lifecycle NOT IN ('ACTIVE','RETIRED') OR length(trim(system))=0 OR length(trim(source_scope))=0 OR length(trim(external_id))=0 LIMIT 1",'Invalid external identity')
 add("WITH RECURSIVE paths(start,id) AS (SELECT alias_canonical_id,canonical_id FROM hub_entity_aliases UNION SELECT p.start,a.canonical_id FROM paths p JOIN hub_entity_aliases a ON a.alias_canonical_id=p.id) SELECT 1 FROM paths WHERE start=id LIMIT 1",'Canonical alias cycle')
 add("SELECT 1 FROM hub_entity_aliases a LEFT JOIN hub_entities s ON s.canonical_id=a.alias_canonical_id LEFT JOIN hub_entities t ON t.canonical_id=a.canonical_id WHERE s.canonical_id IS NULL OR t.canonical_id IS NULL OR s.entity_kind<>t.entity_kind OR s.lifecycle<>'MERGED' LIMIT 1",'Orphan or invalid canonical alias')
 # Malformed legacy Timer JSON must not prevent independent Since When startup.
 # Valid snapshots must have complete, correctly typed identities for addressable alert rules.
 rules="json_each(CASE WHEN json_valid(s.json) THEN s.json ELSE '{}' END,'$.timeFenceRules')"
 add(f"SELECT 1 FROM snapshot s,{rules} r LEFT JOIN hub_entities e ON e.local_table='snapshot.timeFenceRules' AND e.local_key=CAST(json_extract(r.value,'$.id') AS TEXT) AND e.entity_kind='timer/alert' WHERE s.id=1 AND e.canonical_id IS NULL LIMIT 1",'Timer alert registry coverage failed')
 add(f"SELECT 1 FROM snapshot s,{rules} r WHERE s.id=1 GROUP BY json_extract(r.value,'$.id') HAVING count(*)>1 LIMIT 1",'Duplicate Timer alert local ID')
 return queries

if __name__ == '__main__':
 (ASSETS/'canonical-identity-triggers.json').write_text(json.dumps(statements(),indent=2)+'\n')
 (ASSETS/'canonical-identity-validation.json').write_text(json.dumps(validation_queries(),indent=2)+'\n')
 print(f'{len(ENTITIES)} canonical tables; {len(statements())} identity triggers')
