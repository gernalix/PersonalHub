package com.supercontacts.app.ui.contacts

import com.supercontacts.app.data.repository.ContactsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ContactMessagingCapsule(
    private val repository: ContactsRepository,
    private val status: ContactOperationStatusCapsule,
    private val scope: CoroutineScope,
) : ContactMessagingOwner {
    override fun scanMessagingLinks(contactId: Long) {
        scope.launch {
            status.save {
                repository.scanMessagingLinksForContact(contactId)
            }
        }
    }

    override fun scanAllMessagingLinks() {
        scope.launch {
            status.save {
                repository.scanMessagingLinksForAllContacts()
            }
        }
    }

    override fun updateMessagingLinkVerificationStatus(linkId: Long, verificationStatus: String) {
        scope.launch {
            status.save {
                repository.updateMessagingLinkVerificationStatus(linkId, verificationStatus)
            }
        }
    }
}
