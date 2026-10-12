package com.binarybeast.chatapp.data

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * تشفير الرسايل بين الطرفين: ECDH (P-256) ثابت-ثابت + AES-256-GCM.
 * المفتاح الخاص بيتخزن مشفّر على الجهاز، والعام بيتنشر في profiles.public_key.
 *
 * ملاحظة: ده مش Signal Protocol — مفيش forward secrecy ولا تحقق من هوية المفاتيح.
 */
object CryptoBox {
    private lateinit var appContext: Context
    private var pair: KeyPair? = null
    private val peerKeyCache = HashMap<String, PublicKey>()
    private val rng = SecureRandom()

    fun init(ctx: Context) { appContext = ctx.applicationContext }

    private fun prefs() = EncryptedSharedPreferences.create(
        appContext, "keys",
        MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Synchronized
    private fun keyPair(): KeyPair {
        pair?.let { return it }
        val p = prefs()
        val priv = p.getString("priv", null)
        val pub = p.getString("pub", null)
        val kf = KeyFactory.getInstance("EC")
        val kp = if (priv != null && pub != null) {
            KeyPair(
                kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))),
            )
        } else {
            val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
            gen.generateKeyPair().also {
                p.edit()
                    .putString("priv", Base64.encodeToString(it.private.encoded, Base64.NO_WRAP))
                    .putString("pub", Base64.encodeToString(it.public.encoded, Base64.NO_WRAP))
                    .apply()
            }
        }
        pair = kp
        return kp
    }

    fun myPublicKeyB64(): String = Base64.encodeToString(keyPair().public.encoded, Base64.NO_WRAP)

    private fun peerKey(b64: String): PublicKey = peerKeyCache.getOrPut(b64) {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(b64, Base64.NO_WRAP)))
    }

    private fun sharedAesKey(peerPubB64: String): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair().private as PrivateKey)
        ka.doPhase(peerKey(peerPubB64), true)
        val secret = ka.generateSecret()
        val key = MessageDigest.getInstance("SHA-256").digest(secret + "chatapp-v1".toByteArray())
        return SecretKeySpec(key, "AES")
    }

    /** يرجّع base64(iv || ciphertext). */
    fun encrypt(peerPubB64: String, plain: String, aad: String = ""): String {
        val iv = ByteArray(12).also { rng.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, sharedAesKey(peerPubB64), GCMParameterSpec(128, iv))
        c.updateAAD(aad.toByteArray())
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    /** aad = "المرسل>المستلم": بيربط الرسالة باتجاهها فمفيش حد يقدر يعيد رسالة A→B على إنها B→A. */
    fun decrypt(peerPubB64: String, cipherB64: String, aad: String = ""): String {
        val raw = Base64.decode(cipherB64, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, sharedAesKey(peerPubB64), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        c.updateAAD(aad.toByteArray())
        return String(c.doFinal(raw, 12, raw.size - 12))
    }
}
