package com.supercontacts.app.data.repository

data class ContactDuplicateCandidate(
    val contactId: Long,
    val displayName: String,
    val reasons: List<ContactDuplicateReason>,
    val hasStrongMatch: Boolean,
)

enum class ContactDuplicateReason {
    SameNumber,
    SameEmail,
    SimilarName,
    ExistingLink,
    SimilarAddress,
}
