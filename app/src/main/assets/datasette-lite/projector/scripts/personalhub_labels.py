"""Human labels for the existing projection; foreign-key columns remain native keys."""
from datetime import datetime, timezone

LABEL_VERSION = 3
DERIVED = {
    'contact_fields', 'sessions', 'quick_event_entries', 'word_entries',
    'timer_chronology_sessions', 'timer_running_sessions', 'timer_quick_event_entries',
    'timer_chain_steps',
}
TIMES = ('saved_at_ms', 'timestamp_ms', 'started_at_utc_ms', 'created_at_utc_ms', 'start_ms', 'created_at_ms', 'created_at')


def readable_time(value):
    try:
        return datetime.fromtimestamp(value / 1000, timezone.utc).isoformat(timespec='milliseconds').replace('+00:00', 'Z')
    except (ValueError, TypeError, OverflowError, OSError):
        return ''


def human_label(table, values, record_id, preferred, state='active'):
    short = record_id[:8]
    title = table.replace('_', ' ').capitalize()
    timestamp = next((readable_time(values[k]) for k in TIMES if values.get(k) is not None), '')
    if table == 'snapshot_payloads':
        label = f"Snapshot {(values.get('hash') or short)[:12]}"
        if values.get('size_bytes') is not None: label += f" · {values['size_bytes']} B"
    elif table == 'snapshot_history':
        label = ' · '.join(filter(None, ('Snapshot ' + str(values.get('kind') or 'history'), timestamp, str(values.get('payload_hash') or short)[:12])))
    elif table == 'wordpulse_sessions':
        label = 'WordPulse · ' + (timestamp or short)
    elif table == 'health_import_batches':
        label = 'Health import · ' + str(values.get('source_system') or short)
    elif table == 'health_events':
        label = ' · '.join(filter(None, (str(values.get('title_it') or values.get('event_kind') or 'Health event'), timestamp or short)))
    elif table == 'health_samples':
        label = str(values.get('material_it') or values.get('sample_kind') or ('Health sample · ' + short))
    elif table == 'health_examinations':
        label = str(values.get('display_name_it') or values.get('canonical_name') or ('Examination · ' + short))
    elif table == 'health_measurements':
        label = ' · '.join(filter(None, (str(values.get('text_value') or values.get('numeric_value') or 'Measurement'), str(values.get('unit') or ''))))
    elif table == 'health_journal_entries':
        label = str(values.get('title_it') or values.get('note_type_it') or 'Clinical note')
    elif table == 'health_ai_snapshots':
        label = 'Health assessment · ' + str(values.get('subject_kind') or short)
    elif table == 'health_ai_evidence':
        label = 'Health evidence · ' + str(values.get('evidence_kind') or short)
    elif table == 'health_source_metadata':
        label = 'Health metadata · ' + str(values.get('key') or short)
    elif table == 'contact_fields':
        kind = str(values.get('field_type') or 'field').replace('_', ' ')
        text = 'Photo' if kind == 'photo' else str(values.get('value') or values.get('description') or '').strip()[:72]
        label = f"{kind.capitalize()}: {text}".rstrip(': ')
    else:
        label = str(values.get(preferred) or '').strip()
        if table in DERIVED:
            label = next((str(values[k]).strip() for k in ('title', 'original_word', 'name') if values.get(k)), label)
            if table == 'timer_chain_steps':
                label = f"Step {int(values.get('position') or 0) + 1}: {label}".rstrip(': ')
            if timestamp: label = ' · '.join(filter(None, (label, timestamp)))
    # Old generic placeholder/deletion labels must also improve during reconciliation.
    if label.startswith('[deleted] '): label = label[len('[deleted] '):]
    generic = not label or label.lower() in {table.lower(), title.lower()} or label.startswith('[missing') or label.startswith('[deleted ')
    if generic:
        detail = next((str(values[k]).strip()[:60] for k in ('summary', 'description', 'query', 'address', 'kind', 'trigger') if values.get(k)), '')
        label = ' · '.join(filter(None, (title, detail, timestamp)))
    if generic or table in DERIVED or table in {'wordpulse_sessions', 'snapshot_history'}:
        label += ' · #' + short
    if state != 'active': label = '[' + state + '] ' + label
    return label
