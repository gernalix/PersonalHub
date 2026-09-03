#!/usr/bin/env python3
"""Build a standalone PersonalHub DB from audited, coherent original-app exports.

No Android migration, network, synthetic domain records, or third-party dependencies.
The checked-in Room schema is the only target schema definition.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import pathlib
import sqlite3
import tempfile
import os
import mimetypes

REPO = pathlib.Path(__file__).resolve().parents[1]
SCHEMA = REPO / 'core/database/schemas/com.gernalix.personalhub.core.database.PersonalHubDatabase/2.json'
IGNORED = {'android_metadata', 'room_master_table', 'sqlite_sequence'}

def quote(value):
    return '"' + value.replace('"', '""') + '"'

def digest(file):
    with open(file, 'rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def row_digest(rows):
    return hashlib.sha256('\n'.join(sorted(repr(row) for row in rows)).encode()).hexdigest()

def inventory(connection):
    result = {}
    for (table,) in connection.execute("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name").fetchall():
        if table in IGNORED or table.startswith('sqlite_'):
            continue
        columns = connection.execute(f'PRAGMA table_info({quote(table)})').fetchall()
        rows = connection.execute(f'SELECT * FROM {quote(table)}').fetchall()
        timestamps = {}
        for column in columns:
            name = column[1]
            if any(key in name for key in ('timestamp', '_at', '_ms', '_utc', 'epoch_day')):
                timestamps[name] = connection.execute(f'SELECT MAX({quote(name)}) FROM {quote(table)}').fetchone()[0]
        result[table] = {'columns': columns, 'foreign_keys': connection.execute(f'PRAGMA foreign_key_list({quote(table)})').fetchall(), 'count': len(rows), 'rows_sha256': row_digest(rows), 'latest': timestamps}
    return result

def build(args):
    schema = json.loads(SCHEMA.read_text())['database']
    output = args.output.resolve()
    if output.exists():
        raise ValueError('Output exists; choose a new path to preserve it')
    output.parent.mkdir(parents=True, exist_ok=True)
    fd, staging_name = tempfile.mkstemp(prefix='.personalhub-', suffix='.db', dir=output.parent)
    os.close(fd)
    stage = pathlib.Path(staging_name)
    report = {'sources': {}, 'tables': {}, 'photos': {'expected': 0, 'imported': 0, 'missing': []}}
    target = sqlite3.connect(stage)
    try:
        target.execute('PRAGMA foreign_keys=OFF')
        target.execute('BEGIN')
        for entity in schema['entities']:
            target.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
            for index in entity.get('indices', []):
                target.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for query in schema['setupQueries']:
            target.execute(query)
        target.execute('INSERT INTO hub_generation(id,generation) VALUES (1,0)')
        copied = set()
        source_names = ('people', 'timer', 'timer_sync', 'places', 'substances', 'wordpulse')
        for label in source_names:
            path = getattr(args, label).resolve()
            if not path.is_file():
                raise ValueError(f'Missing source: {label}')
            if any(p.exists() and p.stat().st_size > 0 for p in (pathlib.Path(str(path)+'-wal'), pathlib.Path(str(path)+'-journal'))):
                raise ValueError(f'{label}: provide a coherent standalone SQLite backup, not an unchecked live file')
            before = digest(path)
            source = sqlite3.connect(path.as_uri()+'?mode=ro', uri=True)
            try:
                source.execute('BEGIN')
                if source.execute('PRAGMA quick_check').fetchall() != [('ok',)] or source.execute('PRAGMA foreign_key_check').fetchall():
                    raise ValueError(f'Source integrity failure: {label}')
                source_info = inventory(source)
                report['sources'][label] = {'path': str(path), 'sha256': before, 'user_version': source.execute('PRAGMA user_version').fetchone()[0], 'tables': source_info}
                for old, info in source_info.items():
                    new = 'wordpulse_sessions' if label == 'wordpulse' and old == 'sessions' else old
                    if new in copied:
                        raise ValueError(f'Unresolved table collision: {new}')
                    copied.add(new)
                    columns = [column[1] for column in info['columns']]
                    target_columns = {row[1] for row in target.execute(f'PRAGMA table_info({quote(new)})')}
                    if set(columns) != target_columns:
                        raise ValueError(f'Source/target column mismatch: {label}.{old}')
                    rows = source.execute(f'SELECT * FROM {quote(old)}').fetchall()
                    target.executemany(f'INSERT INTO {quote(new)} ({",".join(map(quote,columns))}) VALUES ({",".join("?" for _ in columns)})', rows)
                    readback = target.execute(f'SELECT {",".join(map(quote,columns))} FROM {quote(new)}').fetchall()
                    if row_digest(rows) != row_digest(readback):
                        raise ValueError(f'Row values differ: {label}.{old}')
                    report['tables'][new] = {'module': label, 'source_table': old, 'source_count': len(rows), 'target_count': len(readback), 'values_identical': True, 'latest': info['latest']}
                # Preserve AUTOINCREMENT high-water marks even when the highest row was deleted.
                if source.execute("SELECT 1 FROM sqlite_schema WHERE name='sqlite_sequence'").fetchone():
                    for old, sequence in source.execute('SELECT name,seq FROM sqlite_sequence'):
                        new = 'wordpulse_sessions' if label == 'wordpulse' and old == 'sessions' else old
                        if new not in copied:
                            continue
                        target.execute('DELETE FROM sqlite_sequence WHERE name=?', (new,))
                        target.execute('INSERT INTO sqlite_sequence(name,seq) VALUES (?,?)', (new, sequence))
                # Preserve source read-only views (timer UTC export projections).
                for name, sql in source.execute("SELECT name,sql FROM sqlite_schema WHERE type='view'"):
                    target.execute(sql)
            finally:
                source.close()
            if digest(path) != before:
                raise ValueError(f'Source changed while reading: {label}')
        expected = {e['tableName'] for e in schema['entities']} - {'hub_generation', 'people_photos', 'hub_preferences'}
        if copied != expected:
            raise ValueError(f'Uncovered target tables: {expected-copied}; unexpected: {copied-expected}')
        if args.people_preferences:
            import xml.etree.ElementTree as ET
            prefs_path = args.people_preferences.resolve()
            prefs = {}
            for item in ET.parse(prefs_path).getroot():
                key = item.attrib['name']
                if key not in ('sort', 'sort_direction', 'show_added_edited'):
                    raise ValueError(f'Unrecognized people preference: {key}')
                prefs[key] = item.attrib['value'] == 'true' if item.tag == 'boolean' else item.text
            target.execute('INSERT INTO hub_preferences(namespace,json) VALUES(?,?)', ('supercontacts_home', json.dumps(prefs, sort_keys=True)))
            report['people_preferences'] = {'path': str(prefs_path), 'sha256': digest(prefs_path), 'count': len(prefs)}
        photos = target.execute("SELECT id,contact_id,value,added_at FROM contact_fields WHERE field_type='photo'").fetchall()
        report['photos']['expected'] = len(photos)
        for field_id, contact_id, reference, created_at in photos:
            refpath = pathlib.PurePosixPath(reference)
            if '..' in refpath.parts:
                raise ValueError('Unsafe photo reference')
            photo = args.photos_dir / refpath.name
            if not photo.is_file():
                report['photos']['missing'].append({'field_id':field_id,'reference':reference})
                continue
            data = photo.read_bytes()
            mime = mimetypes.guess_type(photo.name)[0]
            if not data or mime not in ('image/jpeg', 'image/png', 'image/webp'):
                raise ValueError(f'Unsupported photo: {photo.name}')
            sha = hashlib.sha256(data).hexdigest()
            target.execute('INSERT INTO people_photos(field_id,contact_id,reference,mime_type,bytes,sha256,created_at) VALUES (?,?,?,?,?,?,?)', (field_id,contact_id,reference,mime,data,sha,created_at))
            actual = target.execute('SELECT bytes,sha256 FROM people_photos WHERE field_id=?',(field_id,)).fetchone()
            if actual != (data,sha):
                raise ValueError('Photo BLOB differs')
            report['photos']['imported'] += 1
        if report['photos']['missing'] and not args.allow_missing_photos:
            raise ValueError('Missing referenced photos; explicit --allow-missing-photos is required')
        target.execute(f'PRAGMA user_version={schema["version"]}')
        target.commit()
        target.execute('PRAGMA foreign_keys=ON')
        report['quick_check'] = target.execute('PRAGMA quick_check').fetchall()
        report['foreign_key_check'] = target.execute('PRAGMA foreign_key_check').fetchall()
        if report['quick_check'] != [('ok',)] or report['foreign_key_check']:
            raise ValueError('Target integrity failure')
        target.close()
        with open(stage,'rb') as stream: os.fsync(stream.fileno())
        os.replace(stage,output)
        report.update(output=str(output), bytes=output.stat().st_size, sha256=digest(output), status='PASS_WITH_AUTHORIZED_MISSING_PHOTOS' if report['photos']['missing'] else 'PASS')
        report_path = output.with_suffix('.validation.json')
        report_path.write_text(json.dumps(report,indent=2)+'\n')
        print(json.dumps({key:report[key] for key in ('status','output','bytes','sha256','photos')},indent=2))
    finally:
        target.close()
        stage.unlink(missing_ok=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for source in ('people','timer','timer_sync','places','substances','wordpulse'):
        parser.add_argument('--'+source.replace('_','-'),type=pathlib.Path,required=True)
    parser.add_argument('--people-preferences',type=pathlib.Path)
    parser.add_argument('--photos-dir',type=pathlib.Path,required=True)
    parser.add_argument('--output',type=pathlib.Path,required=True)
    parser.add_argument('--allow-missing-photos',action='store_true')
    build(parser.parse_args())

if __name__ == '__main__':
    main()
