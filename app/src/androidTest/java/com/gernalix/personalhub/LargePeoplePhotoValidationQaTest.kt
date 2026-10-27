package com.gernalix.personalhub

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gernalix.personalhub.core.database.DatabaseVault
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator-only safety regression: original image bytes may exceed CursorWindow. */
@RunWith(AndroidJUnit4::class)
class LargePeoplePhotoValidationQaTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun requireIsolatedEmulator() {
        check(context.packageName == "com.gernalix.personalhub.qa")
        check(Build.HARDWARE in setOf("goldfish", "ranchu") &&
            Build.MODEL.contains("sdk", ignoreCase = true))
    }

    private fun digestOfZeros(size: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(64 * 1024)
        repeat(size / chunk.size) { digest.update(chunk) }
        val remainder = size % chunk.size
        if (remainder > 0) digest.update(chunk, 0, remainder)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test fun largeOriginalPhotoValidatesAndWrongDigestIsStillRejected() {
        requireIsolatedEmulator() // first, before SQLite access
        val db = PersonalHubDatabase.get(context).openHelper.writableDatabase
        val id = -957160
        val sizeBytes = 3 * 1024 * 1024
        val sha = digestOfZeros(sizeBytes)
        db.execSQL("INSERT INTO contacts(id,public_id,created_at,updated_at) " +
            "VALUES(?, 'qa-large-photo',1,1)", arrayOf<Any>(id))
        try {
            db.execSQL("INSERT INTO contact_fields(id,contact_id,field_type,value,added_at,position,is_primary) " +
                "VALUES(?,?, 'photo','synthetic',1,0,0)", arrayOf<Any>(id, id))
            db.execSQL("INSERT INTO people_photos(field_id,contact_id,reference,mime_type,bytes,sha256,created_at) " +
                "VALUES(?,?, 'qa-large-fixture','image/jpeg',zeroblob(?),?,1)",
                arrayOf<Any>(id,id,sizeBytes,sha))
            val copied = DatabaseVault.backupCurrent(context)
            try {
                assertTrue(copied.isFile && copied.length() > sizeBytes)
                assertEquals(DatabaseVault.currentGeneration(context), DatabaseVault.validate(context, copied))
            } finally {
                copied.delete()
            }

            // Chunking must never silently accept corrupt original bytes or hashes.
            db.execSQL("UPDATE people_photos SET sha256='invalid-digest' WHERE field_id=?",
                arrayOf<Any>(id))
            val error = assertThrows(IllegalArgumentException::class.java) {
                DatabaseVault.backupCurrent(context)
            }
            assertTrue(error.message.orEmpty().contains("Photo integrity check failed"))
        } finally {
            db.execSQL("DELETE FROM contacts WHERE id=?", arrayOf<Any>(id))
        }
    }
}
