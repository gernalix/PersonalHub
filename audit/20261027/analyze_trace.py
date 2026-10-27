#!/usr/bin/env python3
"""Query the already-recorded synthetic Android Perfetto trace; no new emulator."""
import json, os, pathlib
from perfetto.trace_processor import TraceProcessor
source=pathlib.Path("trace-input/trace.pftrace")
assert source.stat().st_size>1000, "recorded trace is missing"
dest=pathlib.Path("audit/20261027/trace-results")
dest.mkdir(parents=True,exist_ok=True)
queries={
 "process":("SELECT name, pid FROM process WHERE name LIKE '%personalhub%' LIMIT 20",["name","pid"]),
 "ph_sections":("SELECT name, COUNT(*) AS calls, ROUND(SUM(dur)/1e6,3) AS total_ms, ROUND(MAX(dur)/1e6,3) AS max_ms FROM slice WHERE name LIKE 'PH.%' GROUP BY name ORDER BY total_ms DESC LIMIT 75",["name","calls","total_ms","max_ms"]),
 "app_cpu_threads":("SELECT t.name AS thread, ROUND(SUM(s.dur)/1e6,3) AS cpu_ms FROM sched s JOIN thread t ON s.utid=t.utid JOIN process p ON t.upid=p.upid WHERE p.name LIKE '%personalhub%' GROUP BY t.name ORDER BY cpu_ms DESC LIMIT 30",["thread","cpu_ms"]),
 "main_long_slices":("SELECT s.name AS name, ROUND(s.dur/1e6,3) AS ms FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid JOIN process p ON t.upid=p.upid WHERE p.name LIKE '%personalhub%' AND t.name='main' AND s.dur >= 10000000 ORDER BY s.dur DESC LIMIT 40",["name","ms"]),
 "app_long_slices":("SELECT t.name AS thread,t.tid AS tid,s.name AS name,ROUND(s.dur/1e6,3) AS ms FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid JOIN process p ON t.upid=p.upid WHERE p.name LIKE '%personalhub%' AND s.dur>=100000000 ORDER BY s.dur DESC LIMIT 70",["thread","tid","name","ms"]),
 "actual_main_slices":("SELECT t.name AS thread,t.tid AS tid,s.name AS name,ROUND(s.dur/1e6,3) AS ms FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid JOIN process p ON t.upid=p.upid WHERE p.name LIKE '%personalhub%' AND t.tid=p.pid AND s.dur>=10000000 ORDER BY s.dur DESC LIMIT 40",["thread","tid","name","ms"]),
 "monitor_contention":("SELECT t.name AS thread,t.tid AS tid,s.name AS name,ROUND(s.dur/1e6,3) AS ms FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid JOIN process p ON t.upid=p.upid WHERE p.name LIKE '%personalhub%' AND s.name LIKE 'monitor contention%' ORDER BY s.dur DESC LIMIT 15",["thread","tid","name","ms"]),
 "top_slices":("SELECT name, COUNT(*) AS calls, ROUND(SUM(dur)/1e6,3) AS total_ms FROM slice WHERE dur >= 0 GROUP BY name ORDER BY total_ms DESC LIMIT 30",["name","calls","total_ms"]),
}
results={}
with TraceProcessor(trace=str(source)) as tp:
 for label,(query,fields) in queries.items():
  try:
   values=[{field:getattr(row,field,None) for field in fields} for row in tp.query(query)]
   results[label]={"rows":values}
  except Exception as exc:
   results[label]={"error":str(exc)}
(dest/"trace-report.json").write_text(json.dumps(results,indent=2,default=str))
for section,body in results.items():
 print("SECTION",section)
 for row in body.get("rows",[])[:25]: print(json.dumps(row,default=str))
 if "error" in body:print("QUERY_ERROR",body["error"])
