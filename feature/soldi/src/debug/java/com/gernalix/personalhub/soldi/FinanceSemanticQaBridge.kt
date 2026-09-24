package com.gernalix.personalhub.soldi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import com.gernalix.personalhub.core.database.capsules.soldi.AttachmentDraft
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceAccount
import com.gernalix.personalhub.core.database.capsules.soldi.FinanceCapsule
import com.gernalix.personalhub.core.database.capsules.soldi.TransactionDraft
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class FinanceSemanticQaResult(
    val readyIndexes: Int,
    val textTopTransactionId: Long,
    val imageTopTransactionId: Long,
    val ownedItemPersisted: Boolean,
    val nonPhotoExcluded: Boolean,
)

object FinanceSemanticQaBridge {
    suspend fun exercise(context: Context): FinanceSemanticQaResult {
        require(context.packageName.endsWith(".qa")) { "QA bridge requires isolated .qa package" }
        val dbName = "finance-semantic-qa-${UUID.randomUUID()}.db"
        val files = File(context.cacheDir, "finance-semantic-qa-${UUID.randomUUID()}").apply { mkdirs() }
        context.deleteDatabase(dbName)
        var owner: PersonalHubDatabase? = null
        var engine: FinancePhotoSemanticEngine? = null
        try {
            owner = PersonalHubDatabase.openTemporary(context, dbName)
            val finance = FinanceCapsule(owner)
            val accountId = UUID.randomUUID().toString()
            finance.saveAccount(FinanceAccount(id = accountId, name = "Semantic QA", currency = "DKK"))

            val jacketTx = finance.saveTransaction(
                TransactionDraft(title = "Black jacket", amount = "-100", accountId = accountId),
            )
            val shoeTx = finance.saveTransaction(
                TransactionDraft(title = "Brown shoe", amount = "-50", accountId = accountId),
            )

            val jacketFile = File(files, "jacket.png").also {
                writePng(it, jacketBitmap(Color.rgb(244, 244, 244), 0f))
            }
            val queryFile = File(files, "jacket-query.png").also {
                writePng(it, jacketBitmap(Color.rgb(170, 210, 245), 7f))
            }
            val shoeFile = File(files, "shoe.png").also { writePng(it, shoeBitmap()) }
            val fakePdf = File(files, "note.pdf").also { it.writeText("not a photo") }
            val jacketAttachment = finance.addAttachment(
                jacketTx,
                AttachmentDraft("PHOTO_URI", Uri.fromFile(jacketFile).toString(), "jacket", "image/png"),
            )
            val shoeAttachment = finance.addAttachment(
                shoeTx,
                AttachmentDraft("PHOTO_URI", Uri.fromFile(shoeFile).toString(), "shoe", "image/png"),
            )
            val nonPhoto = finance.addAttachment(
                jacketTx,
                AttachmentDraft("DOCUMENT", Uri.fromFile(fakePdf).toString(), "note", "application/pdf"),
            )

            engine = FinancePhotoSemanticEngine(context, finance)
            engine.backfill(listOf(jacketAttachment, shoeAttachment, nonPhoto))
            val indexes = listOfNotNull(
                finance.photoIndex(jacketAttachment.id),
                finance.photoIndex(shoeAttachment.id),
            )
            check(indexes.size == 2 && indexes.all {
                it.status == FinancePhotoSemanticEngine.STATUS_READY
            })
            check(finance.photoIndex(nonPhoto.id) == null)

            val textMatches = engine.searchText("black jacket", indexes)
            check(textMatches.isNotEmpty() && textMatches.first().transactionId == jacketTx)
            val queryBitmap = android.graphics.BitmapFactory.decodeFile(queryFile.absolutePath)
            val imageMatches = try {
                engine.findSimilar(queryBitmap, indexes)
            } finally {
                queryBitmap.recycle()
            }
            check(imageMatches.isNotEmpty() && imageMatches.first().transactionId == jacketTx)
            val jacketScore = imageMatches.first { it.transactionId == jacketTx }.score
            val shoeScore = imageMatches.firstOrNull {
                it.transactionId == shoeTx
            }?.score ?: Float.NEGATIVE_INFINITY
            check(jacketScore > shoeScore)

            val owned = finance.trackOwnedItem(jacketTx, "Black jacket", jacketAttachment.id)
            check(finance.ownedItem(owned.uuid)?.primaryAttachmentId == jacketAttachment.id)

            engine.close()
            engine = null
            owner.close()
            owner = null

            val reopened = PersonalHubDatabase.openTemporary(context, dbName)
            try {
                val reopenedFinance = FinanceCapsule(reopened)
                check(reopenedFinance.photoIndex(jacketAttachment.id)?.status ==
                    FinancePhotoSemanticEngine.STATUS_READY)
                check(reopenedFinance.ownedItem(owned.uuid)?.name == "Black jacket")
                reopenedFinance.removeOwnedItem(owned.uuid)
                check(reopenedFinance.ownedItem(owned.uuid) == null)
            } finally {
                reopened.close()
            }

            return FinanceSemanticQaResult(
                readyIndexes = 2,
                textTopTransactionId = jacketTx,
                imageTopTransactionId = jacketTx,
                ownedItemPersisted = true,
                nonPhotoExcluded = true,
            )
        } finally {
            engine?.close()
            owner?.close()
            context.deleteDatabase(dbName)
            files.deleteRecursively()
        }
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun jacketBitmap(background: Int, rotation: Float): Bitmap =
        Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            canvas.save()
            canvas.rotate(rotation, 160f, 160f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 25, 30) }
            val path = Path().apply {
                moveTo(105f, 70f)
                lineTo(145f, 55f)
                lineTo(175f, 55f)
                lineTo(215f, 70f)
                lineTo(260f, 135f)
                lineTo(225f, 160f)
                lineTo(205f, 130f)
                lineTo(205f, 270f)
                lineTo(115f, 270f)
                lineTo(115f, 130f)
                lineTo(95f, 160f)
                lineTo(60f, 135f)
                close()
            }
            canvas.drawPath(path, paint)
            paint.color = Color.LTGRAY
            paint.strokeWidth = 5f
            canvas.drawLine(160f, 65f, 160f, 265f, paint)
            canvas.restore()
        }

    private fun shoeBitmap(): Bitmap =
        Bitmap.createBitmap(320, 320, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(235, 235, 225))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 70, 35) }
            val path = Path().apply {
                moveTo(55f, 190f)
                lineTo(105f, 165f)
                lineTo(160f, 175f)
                lineTo(205f, 210f)
                lineTo(270f, 220f)
                lineTo(280f, 245f)
                lineTo(245f, 260f)
                lineTo(90f, 255f)
                lineTo(45f, 235f)
                close()
            }
            canvas.drawPath(path, paint)
        }
}
