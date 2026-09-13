package com.wordpulse.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pvt_results",
    indices = [Index(value = ["completed_at_utc_ms"])],
)
data class PvtResultEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "started_at_utc_ms")
    val startedAtUtcMs: Long,
    @ColumnInfo(name = "completed_at_utc_ms")
    val completedAtUtcMs: Long,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,
    @ColumnInfo(name = "trial_count")
    val trialCount: Int,
    @ColumnInfo(name = "median_reaction_time_ms")
    val medianReactionTimeMs: Double?,
    @ColumnInfo(name = "p90_reaction_time_ms")
    val p90ReactionTimeMs: Double?,
    @ColumnInfo(name = "lapse_count")
    val lapseCount: Int,
    @ColumnInfo(name = "false_start_count")
    val falseStartCount: Int,
    @ColumnInfo(name = "paired_fatigue_score")
    val pairedFatigueScore: Int? = null,
    @ColumnInfo(name = "speed_domain_score")
    val speedDomainScore: Int? = null,
    @ColumnInfo(name = "rhythm_domain_score")
    val rhythmDomainScore: Int? = null,
    @ColumnInfo(name = "control_domain_score")
    val controlDomainScore: Int? = null,
    @ColumnInfo(name = "session_drift_domain_score")
    val sessionDriftDomainScore: Int? = null,
    @ColumnInfo(name = "sleep_context_domain_score")
    val sleepContextDomainScore: Int? = null,
    @ColumnInfo(name = "hours_awake")
    val hoursAwake: Double? = null,
)
