package com.gernalix.personalhub.core.database

import androidx.room.*
import com.supercontacts.app.data.local.ContactEntity
import com.supercontacts.app.data.local.ContactFieldEntity

@Entity(tableName = "people_photos", foreignKeys = [
    ForeignKey(entity = ContactEntity::class, parentColumns = ["id"], childColumns = ["contact_id"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ContactFieldEntity::class, parentColumns = ["id"], childColumns = ["field_id"], onDelete = ForeignKey.CASCADE),
], indices = [Index("contact_id"), Index("reference")])
data class PeoplePhoto(
    @PrimaryKey @ColumnInfo(name = "field_id") val fieldId: Long,
    @ColumnInfo(name = "contact_id") val contactId: Long,
    val reference: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    val bytes: ByteArray,
    val sha256: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface PeoplePhotoDao {
    @Query("SELECT * FROM people_photos WHERE reference = :reference LIMIT 1")
    fun find(reference: String): PeoplePhoto?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(photo: PeoplePhoto)
    @Query("DELETE FROM people_photos WHERE reference = :reference")
    fun delete(reference: String)
}
