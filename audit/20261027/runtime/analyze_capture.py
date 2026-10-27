#!/usr/bin/env python3
"""Read the prior captured trace with Perfetto TraceProcessor and write concise evidence."""
import json
import pathlib
import statistics

folder = pathlib.Path("audit/20261027/runtime/results")
starts = json.loads((folder / "startup.json").read_text())
export = json.loads((folder / "export-metrics.json").read_text())
result = {
    "device": json.loads((folder / "device.json").read_text()),
    "baseline_cold_start_ms": [r["total_time_ms"] for r in starts if r["phase"] == "initial-empty"],
    "populated_cold_start_ms": [r["total_time_ms"] for r in starts if r["phase"] == "synthetic-48mib"],
    "populated_startup_median_ms": statistics.median(
        r["total_time_ms"] for r in starts if r["phase"] == "synthetic-48mib"),
    "database_export_metrics": export,
    "security": json.loads((folder / "security.json").read_text()),
    "limits": "Emulator QA, single synthetic database, no real SAF/Pixel or battery measurements",
}
trace = folder / "runtime-startup.pftrace"
if trace.is_file() and trace.stat().st_size > 1000:
    try:
        from perfetto.trace_processor import TraceProcessor
        queries = {
            "app_sections": """
                SELECT s.name AS name, COUNT(*) AS calls, ROUND(SUM(s.dur)/1e6,3) AS total_ms,
                    ROUND(MAX(s.dur)/1e6,3) AS max_ms
                FROM slice s JOIN thread_track tt ON s.track_id=tt.id
                JOIN thread t ON tt.utid=t.utid JOIN process p ON t.upid=p.upid
                WHERE p.name LIKE '%personalhub%' AND s.name LIKE 'PH.%'
                GROUP BY s.name ORDER BY total_ms DESC LIMIT 30
            """,
            "main_frame_slices": """
                SELECT s.name AS name, ROUND(s.dur/1e6,3) AS duration_ms
                FROM slice s JOIN thread_track tt ON s.track_id=tt.id
                JOIN thread t ON tt.utid=t.utid JOIN process p ON t.upid=p.upid
                WHERE p.name LIKE '%personalhub%' AND t.tid=p.pid
                  AND s.name LIKE 'Choreographer#doFrame%' AND s.dur >= 0
                ORDER BY s.dur DESC LIMIT 30
            """,
            "app_cpu_threads": """
                SELECT t.name AS thread, ROUND(SUM(s.dur)/1e6,3) AS cpu_ms
                FROM sched s JOIN thread t ON s.utid=t.utid
                JOIN process p ON t.upid=p.upid WHERE p.name LIKE '%personalhub%'
                GROUP BY t.name ORDER BY cpu_ms DESC LIMIT 20
            """,
        }
        with TraceProcessor(trace=str(trace)) as tp:
            for key, sql in queries.items():
                result[key] = [dict(row) for row in tp.query(sql)]
        result["trace_bytes"] = trace.stat().st_size
    except Exception as exc:
        result["perfetto_analysis_error"] = str(exc)
else:
    result["perfetto_analysis_error"] = "No captured trace file"
(folder / "report.json").write_text(json.dumps(result, indent=2, default=str))
print(json.dumps({k: v for k, v in result.items() if k != "device"}, indent=2, default=str)[:19000])
