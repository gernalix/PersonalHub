package com.gernalix.personalhub.core.database.capsules.soldi

import androidx.room.withTransaction
import com.gernalix.personalhub.core.database.PersonalHubDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.time.Instant

/** Versioned finance-only interchange. Missing rows never delete local records. */
class FinanceExchange(private val db: PersonalHubDatabase) {
    private val dao = db.financeDao()
    private val finance = FinanceCapsule(db)
    suspend fun export(): String = db.withTransaction {
        val accounts = dao.allAccounts().map { a -> obj("id" to a.id,"name" to a.name,"currency" to a.currency,"openingBalance" to a.openingBalance,"openedAt" to a.openedAt) }
        val products = dao.allProducts().map { p -> obj("id" to p.uuid,"name" to p.name) }
        val transactions = dao.allTransactions().map { t -> obj("id" to t.uuid,"accountId" to t.accountId,"productId" to t.productId?.let { dao.product(it)!!.uuid },
            "title" to t.titleId?.let { dao.titleName(it) },"amount" to t.amount,"currency" to t.currency,"chain" to t.chainId?.let { dao.chainName(it) },
            "placeId" to t.placeId,"notes" to t.notes,"tags" to dao.tags(t.id).joinToString(","),"fromReceipt" to t.fromReceipt,
            "occurredAt" to t.occurredAt,"createdAt" to t.createdAt,"updatedAt" to t.updatedAt) }
        canonical(obj("version" to 1,"accounts" to JSONArray(accounts),"products" to JSONArray(products),"transactions" to JSONArray(transactions))) + "\n"
    }
    suspend fun import(text: String, bases: Map<String, String>): Map<String, String> = db.withTransaction {
        require(text.toByteArray().size <= 2_000_000)
        val input = JSONObject(text)
        keys(input, setOf("version","accounts","products","transactions")); require(input.getInt("version") == 1)
        val current = fingerprints(export())
        val incoming = fingerprints(text)
        incoming.forEach { (key, hash) ->
            val local = current[key]
            if (local != hash) {
                // A prior local deletion or any local edit is a conflict, regardless of remote timestamps.
                require(if (local == null) key !in bases else bases[key] == local) { "Finance conflict" }
            }
        }
        rows(input,"accounts").forEach { a ->
            keys(a,setOf("id","name","currency","openingBalance","openedAt")); uuid(a.getString("id"))
            val old = dao.account(a.getString("id"))
            if(current["accounts/${a.getString("id")}"] != incoming["accounts/${a.getString("id")}"]) finance.saveAccount(FinanceAccount(a.getString("id"),a.getString("name"),a.getString("currency"),a.getString("openingBalance"),a.getString("openedAt"),old?.included ?: true))
        }
        rows(input,"products").forEach { p ->
            keys(p,setOf("id","name")); val id = p.getString("id"); uuid(id)
            val name = p.getString("name"); require(name.isNotBlank() && name == name.trim())
            val old = dao.productByUuid(id)
            if (old == null) dao.add(FinanceProduct(name = name,uuid = id)) else if (old.name != name) dao.update(old.copy(name = name))
        }
        rows(input,"transactions").forEach { t ->
            keys(t,setOf("id","accountId","productId","title","amount","currency","chain","placeId","notes","tags","fromReceipt","occurredAt","createdAt","updatedAt"))
            val uuid = t.getString("id"); uuid(uuid)
            val old = dao.transactionByUuid(uuid)
            val created = FinanceCapsule.utc(t.getString("createdAt")); val updated = FinanceCapsule.utc(t.getString("updatedAt"))
            require(Instant.parse(updated) >= Instant.parse(created))
            if (old != null) require(Instant.parse(updated) >= Instant.parse(old.updatedAt)) { "Stale finance record" }
            if (current["transactions/$uuid"] != incoming["transactions/$uuid"]) {
                val product = nullable(t,"productId")?.let { requireNotNull(dao.productByUuid(it)) }
                val id = finance.saveTransaction(TransactionDraft(id = old?.id,title = nullable(t,"title") ?: product?.name.orEmpty(),
                    isProduct = product != null, amount = t.getString("amount"),currency = t.getString("currency"),chain = nullable(t,"chain").orEmpty(),
                    placeId = nullable(t,"placeId"),notes = t.getString("notes"),tags = t.getString("tags"),fromReceipt = t.getBoolean("fromReceipt"),
                    occurredAt = t.getString("occurredAt"),accountId = t.getString("accountId"),productId = product?.id))
                dao.update(requireNotNull(dao.transaction(id)).copy(uuid = uuid,createdAt = created,updatedAt = updated))
            }
        }
        // Normalization must not silently reinterpret any incoming value.
        val result = fingerprints(export())
        require(incoming.all { (key,hash) -> result[key] == hash }) { "Noncanonical finance input" }
        incoming
    }
    companion object {
        private fun uuid(value: String) { require(UUID.fromString(value).toString() == value) }
        private fun nullable(o: JSONObject,key: String) = if(o.isNull(key)) null else o.getString(key)
        private fun keys(o: JSONObject, allowed: Set<String>) { require(o.keys().asSequence().toSet() == allowed) }
        private fun rows(root: JSONObject,key: String): List<JSONObject> { val a=root.getJSONArray(key); require(a.length()<=10000); return (0 until a.length()).map { a.getJSONObject(it) } }
        fun fingerprints(text: String): Map<String,String> {
            val root = JSONObject(text)
            return buildMap { listOf("accounts","products","transactions").forEach { type -> rows(root,type).forEach { row ->
                val key = "$type/${row.getString("id")}"; require(key !in this); put(key,hash(canonical(row)))
            } } }
        }
        fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        fun obj(vararg entries: Pair<String,Any?>) = JSONObject().apply { entries.forEach { (k,v) -> put(k,v ?: JSONObject.NULL) } }
        fun canonical(value: Any?): String = when(value) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            is Boolean, is Number -> value.toString()
            else -> error("Unsupported value")
        }
    }
}
