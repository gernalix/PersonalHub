package com.gernalix.personalhub.soldi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.gernalix.personalhub.soldi.receipt.ReceiptOcrProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/** On-demand, bounded PDF OCR on device. No attachment or OCR body is retained in the database. */
internal suspend fun readGmailInvoicePdf(context: Context, receipt: GmailReceipt, transport: GmailTransport): GmailReceipt = withContext(Dispatchers.IO) {
    val message = transport.get("messages/${receipt.messageId}", mapOf("format" to "full"))
    val pdfs = mutableListOf<JSONObject>()
    fun visit(part: JSONObject, depth: Int) {
        require(depth <= 12)
        if (part.optString("mimeType") == "application/pdf") pdfs += part
        val parts = part.optJSONArray("parts") ?: return
        require(parts.length() <= 100)
        for (i in 0 until parts.length()) visit(parts.getJSONObject(i), depth + 1)
    }
    visit(message.getJSONObject("payload"), 0)
    require(pdfs.size == 1) { "Apri Gmail e seleziona la fattura: nessun PDF univoco" }
    val body = pdfs.single().getJSONObject("body")
    require(body.optLong("size", 0) in 1..2 * 1024 * 1024)
    val data = body.gmailText("data") ?: body.getString("attachmentId").let { id ->
        require(id.matches(Regex("[A-Za-z0-9_-]{1,500}")))
        transport.get("messages/${receipt.messageId}/attachments/$id").getString("data")
    }
    require(data.length <= 3 * 1024 * 1024)
    val bytes = Base64.getUrlDecoder().decode(data)
    require(bytes.size <= 2 * 1024 * 1024)
    val file = File.createTempFile("invoice-", ".pdf", context.cacheDir)
    val image = File.createTempFile("invoice-", ".png", context.cacheDir)
    try {
        file.writeBytes(bytes)
        val text = StringBuilder()
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount in 1..3) { "Fattura troppo lunga per l’estrazione locale" }
                for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                    val scale = 1600.0 / maxOf(page.width, page.height)
                    val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        text.append(ReceiptOcrProcessor().recognize(context, Uri.fromFile(image))).append('\n')
                    } finally { bitmap.recycle() }
                }
            }
        }
        val synthetic = JSONObject().put("id", receipt.messageId).put("internalDate", receipt.receivedAt.toEpochMilli().toString()).put("payload", JSONObject()
            .put("mimeType", "text/plain").put("headers", JSONArray().put(JSONObject().put("name", "Subject").put("value", "Invoice")))
            .put("body", JSONObject().put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(text.toString().toByteArray()))))
        val parsed = requireNotNull(GmailReceiptParser.parse(synthetic))
        receipt.copy(merchant = parsed.merchant ?: receipt.merchant, orderId = parsed.orderId ?: receipt.orderId,
            date = parsed.date ?: receipt.date, amount = parsed.amount ?: receipt.amount, currency = parsed.currency ?: receipt.currency,
            parser = "PDF locale da verificare")
    } finally { image.delete(); file.delete() }
}
