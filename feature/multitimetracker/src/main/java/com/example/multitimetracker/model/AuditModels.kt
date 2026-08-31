package com.example.multitimetracker.model

data class AuditEventUi(
    val id: Long,
    val tsMs: Long,
    val isSystem: Boolean,
    val summary: String,
    val action: String,
    val payloadJson: String?,
    val undoable: Boolean,
    val isUndone: Boolean
)
