package com.gernalix.personalhub.core.database.capsules.health

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "health_import_batches", indices = [Index(value = ["source_hash"], unique = true)])
data class HealthImportBatches(
    @PrimaryKey val id: String,
    val received_at_ms: Long,
    val imported_at_ms: Long,
    val source_system: String,
    val source_language: String?,
    val source_ref: String?,
    val source_hash: String?,
    val author: String,
    val notes: String?
)

@Entity(tableName = "health_source_metadata", primaryKeys = ["owner_kind", "owner_id", "key"])
data class HealthSourceMetadata(
    val owner_kind: String,
    val owner_id: String,
    val key: String,
    val value: String,
    val source_label: String?
)

@Entity(tableName = "health_events", foreignKeys = [ForeignKey(entity = HealthImportBatches::class, parentColumns = ["id"], childColumns = ["import_batch_id"], onDelete = ForeignKey.SET_NULL), ForeignKey(entity = com.gernalix.luoghi.data.PlaceEntity::class, parentColumns = ["uuid"], childColumns = ["place_id"], onDelete = ForeignKey.SET_NULL), ForeignKey(entity = com.supercontacts.app.data.local.ContactEntity::class, parentColumns = ["id"], childColumns = ["clinician_contact_id"], onDelete = ForeignKey.SET_NULL)], indices = [Index(value = ["import_batch_id"], unique = false), Index(value = ["place_id"], unique = false), Index(value = ["clinician_contact_id"], unique = false)])
data class HealthEvents(
    @PrimaryKey val id: String,
    val import_batch_id: String?,
    val event_kind: String,
    val occurred_at_ms: Long?,
    val utc_offset_min: Int?,
    val time_precision: String,
    val title_it: String?,
    val place_id: String?,
    val clinician_contact_id: Long?,
    val source_system: String,
    val source_ref: String?,
    val created_at_ms: Long,
    val updated_at_ms: Long
)

@Entity(tableName = "health_samples", foreignKeys = [ForeignKey(entity = HealthEvents::class, parentColumns = ["id"], childColumns = ["event_id"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["event_id"], unique = true)])
data class HealthSamples(
    @PrimaryKey val id: String,
    val event_id: String,
    val sample_kind: String,
    val material_it: String?,
    val body_site_it: String?,
    val collection_ref: String?
)

@Entity(tableName = "health_examinations", indices = [Index(value = ["canonical_name"], unique = true)])
data class HealthExaminations(
    @PrimaryKey val id: String,
    val canonical_name: String,
    val display_name_it: String,
    val category_it: String,
    val default_unit: String?
)

@Entity(tableName = "health_measurements", foreignKeys = [ForeignKey(entity = HealthSamples::class, parentColumns = ["id"], childColumns = ["sample_id"], onDelete = ForeignKey.CASCADE), ForeignKey(entity = HealthExaminations::class, parentColumns = ["id"], childColumns = ["examination_id"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["sample_id"], unique = false), Index(value = ["examination_id"], unique = false)])
data class HealthMeasurements(
    @PrimaryKey val id: String,
    val sample_id: String,
    val examination_id: String,
    val numeric_value: Double?,
    val text_value: String?,
    val unit: String?,
    val interpretation_it: String?,
    val flag: String?,
    val result_available_at_ms: Long?,
    val received_at_ms: Long?,
    val availability_basis: String,
    val source_ref: String?
)

@Entity(tableName = "health_journal_entries", foreignKeys = [ForeignKey(entity = HealthEvents::class, parentColumns = ["id"], childColumns = ["event_id"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["event_id"], unique = true)])
data class HealthJournalEntries(
    @PrimaryKey val id: String,
    val event_id: String,
    val authored_at_ms: Long?,
    val authored_utc_offset_min: Int?,
    val authored_time_precision: String?,
    val encounter_type_it: String?,
    val department_it: String?,
    val clinician_role_it: String?,
    val note_type_it: String?,
    val title_it: String?,
    val text_it: String,
    val original_text_da: String,
    val source_ref: String?
)

@Entity(tableName = "health_ai_snapshots", indices = [Index(value = ["subject_kind", "subject_id"], unique = true)])
data class HealthAiSnapshots(
    @PrimaryKey val id: String,
    val subject_kind: String,
    val subject_id: String,
    val as_of_ms: Long,
    val generated_at_ms: Long,
    val generated_by: String,
    val model: String?,
    val assessment_version: Int,
    val stance: String?,
    val comment_it: String,
    val uncertainty_it: String?
)

@Entity(tableName = "health_ai_evidence", primaryKeys = ["snapshot_id", "evidence_kind", "evidence_id"], foreignKeys = [ForeignKey(entity = HealthAiSnapshots::class, parentColumns = ["id"], childColumns = ["snapshot_id"], onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["snapshot_id"], unique = false)])
data class HealthAiEvidence(
    val snapshot_id: String,
    val evidence_kind: String,
    val evidence_id: String,
    val relevance_it: String,
    val position: Int
)
