#!/usr/bin/env python3
"""Entire tracked text tree line sweep and Room 26 in-memory mutation fuzz. Synthetic data only."""
import collections, json, pathlib, random, re, sqlite3, subprocess, xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[2]
files=[pathlib.Path(p) for p in subprocess.check_output(
    ["git","-C",str(root),"ls-files","-z"]).decode().strip("\0").split("\0") if p]
patterns={
    "blocking":r"\brunBlocking\s*\(|Thread\.sleep\s*\(",
    "raw_sql":r"\b(?:rawQuery|execSQL)\s*\(",
    "large_file_read":r"\.(?:readBytes|readText)\s*\(",
    "flow_full_collections":r"Flow<List<|collectAsState\s*\(",
    "external_intent":r"\b(?:startActivity|sendBroadcast)\s*\(",
    "web_requests":r"HttpURLConnection|openConnection\s*\(",
    "swallowed_exception":r"catch\s*\([^)]*Exception[^)]*\)",
    "potential_secret":r"\b(?:gh[pousr]_[A-Za-z0-9]{20,}|AIza[A-Za-z0-9_-]{30,}|sk-[A-Za-z0-9_-]{25,})\b",
}
patterns={k:re.compile(v) for k,v in patterns.items()}
counts=collections.Counter(); hits=collections.defaultdict(list); line_count=0; text_files=0; binaries=0; lengths=[]
for p in files:
    buf=(root/p).read_bytes()
    if b"\x00" in buf: binaries+=1; continue
    try: lines=buf.decode("utf-8").splitlines()
    except UnicodeError: binaries+=1; continue
    text_files+=1; line_count+=len(lines); lengths.append((len(lines),str(p)))
    for no,line in enumerate(lines,1):
        for kind,rx in patterns.items():
            if rx.search(line):
                counts[kind]+=1
                if len(hits[kind])<150: hits[kind].append({"path":str(p),"line":no})
exported=[]; ns="{http://schemas.android.com/apk/res/android}"
for p in files:
    if p.name!="AndroidManifest.xml":continue
    try: manifest=__import__("xml.etree.ElementTree",fromlist=["ElementTree"]).parse(root/p).getroot()
    except Exception:continue
    app=manifest.find("application")
    if app is None: continue
    for child in app:
        if child.tag in {"activity","activity-alias","provider","receiver","service"} and child.get(ns+"exported")=="true":
            exported.append({"manifest":str(p),"name":child.get(ns+"name"),"component":child.tag,"permission":child.get(ns+"permission")})
summary={"tracked_files":len(files),"text_files":text_files,"binary_files":binaries,
    "text_lines_scanned":line_count,"pattern_counts":dict(counts),
    "suspicious_locations_no_secret_values":dict(hits),
    "top_40_longest_text_files":[{"lines":n,"file":f} for n,f in sorted(lengths,reverse=True)[:40]],
    "manifest_exported_components":exported,
    "limitations":"Automated line sweep; not human line-by-line semantic review; pattern hits are not proven bugs."}
(root/"audit/20261027/source-audit.json").write_text(json.dumps(summary,indent=2))
print("SOURCE_SCAN",json.dumps({k:v for k,v in summary.items() if k not in ("suspicious_locations_no_secret_values","top_40_longest_text_files")}))
schema_path=root/"core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase/26.json"
schema=json.loads(schema_path.read_text())["database"]
db=sqlite3.connect(":memory:"); db.execute("PRAGMA foreign_keys=OFF")
placeholder="$"+"{TABLE_NAME}"
for entity in schema["entities"]:
    db.execute(entity["createSql"].replace(placeholder,entity["tableName"]))
nidx=0
for entity in schema["entities"]:
    for index in entity.get("indices",[]):
        db.execute(index["createSql"].replace(placeholder,entity["tableName"])); nidx+=1
for view in schema.get("views",[]): db.execute(view["createSql"])
db.execute("PRAGMA foreign_keys=ON")
assert db.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
assert db.execute("PRAGMA foreign_key_check").fetchall()==[]
rng=random.Random(20261027); ok=0; rejected=0; trials=3000
for _ in range(trials):
    entity=rng.choice(schema["entities"]); table=entity["tableName"]
    cols=[c for c in db.execute('PRAGMA table_info("'+table.replace('"','""')+'")') if c[5]==0]
    if not cols:continue
    col=rng.choice(cols)[1]
    val=rng.choice([None,0,1,-1,2**31,"","☃","' OR 1=1 --","X"*rng.randrange(1,512),rng.random()])
    db.execute("SAVEPOINT fuzz")
    try:
        db.execute('INSERT INTO "'+table.replace('"','""')+'" ("'+col.replace('"','""')+'") VALUES (?)',(val,))
        ok+=1
    except sqlite3.DatabaseError: rejected+=1
    finally:
        db.execute("ROLLBACK TO fuzz"); db.execute("RELEASE fuzz")
assert db.execute("PRAGMA integrity_check").fetchone()[0]=="ok"
assert not db.execute("PRAGMA foreign_key_check").fetchall()
res={"version":schema["version"],"tables":len(schema["entities"]),"indexes":nidx,
    "random_mutations":ok+rejected,"accepted_before_rollback":ok,"constraint_or_sql_rejections":rejected,
    "integrity":"ok","limitation":"In-memory schema tests; excludes Room lifecycle triggers and personal data."}
(root/"audit/20261027/sqlite-fuzz.json").write_text(json.dumps(res,indent=2))
print("SQLITE_FUZZ",json.dumps(res))
