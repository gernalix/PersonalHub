package com.gernalix.personalhub.core.database.capsules.gitdata

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.security.spec.ECGenParameterSpec

/**
 * Device attestation for PH-produced history batches.
 *
 * This signs the immutable history payload, not the Git commit object. The private key never leaves
 * Android Keystore; the repository stores the public SPKI key and signature next to the batch.
 */
internal object GitDataSigner {
    private const val ALIAS = "personalhub.gitdata.history"

    fun signatureDocument(bytes: ByteArray): ByteArray {
        val pair = keyPair()
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.first)
            update(bytes)
            sign()
        }
        return JSONObject()
            .put("format_version", 1)
            .put("algorithm", "SHA256withECDSA")
            .put("key_alias", ALIAS)
            .put("payload_sha256", GitDataFormat.sha256(bytes))
            .put(
                "public_key_spki_base64",
                Base64.encodeToString(pair.second.encoded, Base64.NO_WRAP),
            )
            .put(
                "signature_base64",
                Base64.encodeToString(signature, Base64.NO_WRAP),
            )
            .toString(2)
            .toByteArray(Charsets.UTF_8)
    }

    fun verify(bytes: ByteArray, signatureDocument: ByteArray): Boolean = runCatching {
        val document = JSONObject(String(signatureDocument, Charsets.UTF_8))
        require(document.getInt("format_version") == 1)
        require(document.getString("algorithm") == "SHA256withECDSA")
        require(document.getString("payload_sha256") == GitDataFormat.sha256(bytes))
        val publicBytes = Base64.decode(
            document.getString("public_key_spki_base64"),
            Base64.DEFAULT,
        )
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicBytes))
        val signature = Base64.decode(document.getString("signature_base64"), Base64.DEFAULT)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(bytes)
            verify(signature)
        }
    }.getOrDefault(false)

    private fun keyPair(): Pair<PrivateKey, PublicKey> {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existingPrivate = store.getKey(ALIAS, null) as? PrivateKey
        val existingPublic = store.getCertificate(ALIAS)?.publicKey
        if (existingPrivate != null && existingPublic != null) {
            return existingPrivate to existingPublic
        }
        val pair = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            "AndroidKeyStore",
        ).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
        }.generateKeyPair()
        return pair.private to pair.public
    }
}
