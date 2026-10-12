package com.binarybeast.chatapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.binarybeast.chatapp.data.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.security.SecureRandom

/** قفل التطبيق بـ PIN. بيقفل عند فتح التطبيق أو الرجوع له بعد أكتر من 15 ثانية في الخلفية. */
object AppLock {
    val locked = MutableStateFlow(false)
    private var leftAt = 0L

    fun hasPin() = AppSettings.now.pinHash.isNotEmpty()

    private fun legacyHash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    /** PBKDF2 بدل SHA-256 عادي: بيصعّب تخمين الـ PIN لو حد وصل لملف الإعدادات. */
    private fun kdf(salt: String, pin: String): String {
        val spec = javax.crypto.spec.PBEKeySpec(pin.toCharArray(), salt.toByteArray(), 60_000, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
            .joinToString("") { "%02x".format(it) }
    }

    fun setPin(pin: String?) {
        if (pin == null) { AppSettings.update { it.copy(pinHash = "", pinFails = 0, pinLockUntil = 0) }; return }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        AppSettings.update { it.copy(pinHash = "p2:$salt:${kdf(salt, pin)}", pinFails = 0, pinLockUntil = 0) }
    }

    /** ملي ثانية متبقية على انتهاء القفل المؤقت (0 = مفيش قفل). */
    fun lockedForMs(): Long = (AppSettings.now.pinLockUntil - System.currentTimeMillis()).coerceAtLeast(0)

    fun verify(pin: String): Boolean {
        if (lockedForMs() > 0) return false
        val parts = AppSettings.now.pinHash.split(":")
        val ok = when {
            parts.size == 3 && parts[0] == "p2" -> kdf(parts[1], pin) == parts[2]
            parts.size == 2 -> parts[1].isNotEmpty() && legacyHash(parts[0], pin) == parts[1]
            else -> false
        }
        AppSettings.update { p ->
            if (ok) p.copy(pinFails = 0, pinLockUntil = 0)
            else {
                val f = p.pinFails + 1
                // من المحاولة الخامسة: قفل 30ث ويتضاعف لحد ساعة
                val lock = if (f >= 5) System.currentTimeMillis() + minOf(30_000L shl (f - 5).coerceAtMost(7), 3_600_000L) else 0L
                p.copy(pinFails = f, pinLockUntil = lock)
            }
        }
        return ok
    }

    fun onStart() { if (hasPin() && (leftAt == 0L || System.currentTimeMillis() - leftAt > AppSettings.now.autoLockSec * 1000L)) locked.value = true }
    fun onStop() { leftAt = System.currentTimeMillis() }
}

@Composable
fun PinScreen() {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.binarybeast.chatapp.R.drawable.logo),
            contentDescription = null, modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("ادخل الرقم السري", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8); wrong = false },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = wrong, supportingText = { if (AppLock.lockedForMs() > 0) Text("محاولات كتير. استنى ${AppLock.lockedForMs() / 1000} ثانية") else if (wrong) Text("الرقم غلط") },
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { if (AppLock.verify(pin)) AppLock.locked.value = false else { wrong = true; pin = "" } },
            enabled = pin.length >= 4, modifier = Modifier.fillMaxWidth(),
        ) { Text("فتح") }
    }
}
