CREATE TABLE analyses (
    id INTEGER PRIMARY KEY,
    examination_id INTEGER NOT NULL REFERENCES examinations(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    default_unit TEXT,
    UNIQUE(examination_id, name)
);

CREATE TABLE categories (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE examinations (
    id INTEGER PRIMARY KEY,
    category_id INTEGER NOT NULL REFERENCES categories(id),
    name TEXT NOT NULL,
    UNIQUE(category_id, name)
);

CREATE TABLE materials (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE measurements (
    id INTEGER PRIMARY KEY,
    test_event_id INTEGER NOT NULL REFERENCES test_events(id) ON DELETE CASCADE,
    analysis_id INTEGER NOT NULL REFERENCES analyses(id),
    outcome_id INTEGER REFERENCES result_outcomes(id),
    numeric_value REAL,
    text_value TEXT,
    unit TEXT,
    interpretation TEXT,
    flag TEXT,
    status TEXT,
    received_epoch_ms INTEGER,
    received_utc_offset_min INTEGER,
    received_source TEXT,
    CHECK (
        outcome_id IS NOT NULL OR
        numeric_value IS NOT NULL OR
        text_value IS NOT NULL OR
        interpretation IS NOT NULL OR
        status IS NOT NULL
    ),
    UNIQUE(test_event_id, analysis_id)
);

CREATE TABLE medical_journal_entries (
    id INTEGER PRIMARY KEY,
    encounter_epoch_ms INTEGER,
    encounter_utc_offset_min INTEGER,
    encounter_time_precision TEXT CHECK (
        encounter_time_precision IN ('datetime', 'date', 'unknown')
    ),
    authored_epoch_ms INTEGER,
    authored_utc_offset_min INTEGER,
    authored_time_precision TEXT CHECK (
        authored_time_precision IN ('datetime', 'date', 'unknown')
    ),
    encounter_type_it TEXT,
    department_it TEXT,
    facility_it TEXT,
    clinician_name TEXT,
    clinician_role_it TEXT,
    note_type_it TEXT,
    title_it TEXT,
    text_it TEXT NOT NULL,
    original_text_da TEXT NOT NULL,
    source_system TEXT NOT NULL DEFAULT 'Min Sundhedsplatform / MyChart',
    source_ref TEXT,
    notes TEXT
);

CREATE TABLE medical_journal_metadata (
    entry_id INTEGER NOT NULL
        REFERENCES medical_journal_entries(id) ON DELETE CASCADE,
    key TEXT NOT NULL,
    value TEXT NOT NULL,
    PRIMARY KEY (entry_id, key)
);

CREATE TABLE medical_journal_snapshot_entries (
    snapshot_id INTEGER NOT NULL
        REFERENCES medical_journal_snapshots(id) ON DELETE CASCADE,
    related_entry_id INTEGER NOT NULL
        REFERENCES medical_journal_entries(id) ON DELETE CASCADE,
    relevance_it TEXT NOT NULL,
    PRIMARY KEY (snapshot_id, related_entry_id)
);

CREATE TABLE medical_journal_snapshot_measurements (
    snapshot_id INTEGER NOT NULL
        REFERENCES medical_journal_snapshots(id) ON DELETE CASCADE,
    measurement_id INTEGER NOT NULL
        REFERENCES measurements(id) ON DELETE CASCADE,
    relevance_it TEXT NOT NULL,
    PRIMARY KEY (snapshot_id, measurement_id)
);

CREATE TABLE medical_journal_snapshots (
    id INTEGER PRIMARY KEY,
    entry_id INTEGER NOT NULL UNIQUE
        REFERENCES medical_journal_entries(id) ON DELETE CASCADE,
    as_of_epoch_ms INTEGER NOT NULL,
    generated_epoch_ms INTEGER NOT NULL,
    generated_by TEXT NOT NULL DEFAULT 'ChatGPT',
    model TEXT,
    assessment_version INTEGER NOT NULL DEFAULT 1,
    snapshot_it TEXT NOT NULL,
    uncertainties_it TEXT,
    CHECK (assessment_version >= 1)
);

CREATE TABLE metadata (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);

CREATE TABLE result_outcomes (
    id INTEGER PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    source_label TEXT NOT NULL UNIQUE,
    display_it TEXT NOT NULL,
    description_it TEXT
);

CREATE TABLE sample_comments (
    id INTEGER PRIMARY KEY,
    sample_id INTEGER NOT NULL REFERENCES samples(id) ON DELETE CASCADE,
    comment_type TEXT NOT NULL,
    author_label TEXT NOT NULL,
    comment_it TEXT NOT NULL,
    source TEXT NOT NULL,
    recorded_epoch_ms INTEGER NOT NULL
);

CREATE TABLE samples (
    id INTEGER PRIMARY KEY,
    collection_epoch_ms INTEGER NOT NULL,
    utc_offset_min INTEGER NOT NULL DEFAULT 0,
    time_precision TEXT NOT NULL DEFAULT 'datetime' CHECK (
        time_precision IN ('datetime', 'date', 'unknown')
    ),
    material_id INTEGER REFERENCES materials(id),
    source TEXT,
    notes TEXT
);

CREATE TABLE test_events (
    id INTEGER PRIMARY KEY,
    event_epoch_ms INTEGER NOT NULL,
    utc_offset_min INTEGER NOT NULL DEFAULT 0,
    event_time_precision TEXT NOT NULL DEFAULT 'datetime' CHECK (
        event_time_precision IN ('datetime', 'date', 'unknown')
    ),
    examination_id INTEGER NOT NULL REFERENCES examinations(id),
    sample_id INTEGER REFERENCES samples(id),
    material_id INTEGER REFERENCES materials(id),
    location TEXT,
    overall_result TEXT,
    status TEXT,
    source TEXT,
    notes TEXT
);
