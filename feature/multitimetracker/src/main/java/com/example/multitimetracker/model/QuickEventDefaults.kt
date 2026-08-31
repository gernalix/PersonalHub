package com.example.multitimetracker.model

object QuickEventDefaults {
    fun requiredFieldsMissingDefaults(fields: List<QuickEventFieldDefinition>): Boolean =
        fields.any { it.deletedAtMs == null && it.required && it.defaultValue.isBlank() }

    fun defaultValuesFor(entryId: Long, fields: List<QuickEventFieldDefinition>): List<QuickEventFieldValue> =
        fields
            .filter { it.deletedAtMs == null }
            .sortedBy { it.displayOrder }
            .mapIndexed { index, field ->
                QuickEventFieldValue(
                    id = 0L,
                    entryId = entryId,
                    fieldId = field.id,
                    label = field.label,
                    type = field.type,
                    value = field.defaultValue,
                    displayOrder = field.displayOrder.takeIf { it != 0 } ?: index
                )
            }
}
