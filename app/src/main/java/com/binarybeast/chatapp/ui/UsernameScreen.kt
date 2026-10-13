package com.binarybeast.chatapp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.binarybeast.chatapp.data.ProfileRepository
import kotlinx.coroutines.launch

/** بتظهر مرة واحدة بعد أول دخول: المستخدم يختار الاسم واليوزر نيم. */
@Composable
fun UsernameScreen(defaultName: String, session: SessionVm) {
    var name by remember { mutableStateOf(defaultName) }
    var uname by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("أهلاً بيك في ATHEER 👋", style = MaterialTheme.typography.headlineSmall)
        Text("اختار اسمك واليوزر نيم بتاعك", Modifier.padding(top = 8.dp, bottom = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it.take(100) }, label = { Text("الاسم") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            uname, { uname = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20); error = null },
            label = { Text("اليوزر نيم") }, prefix = { Text("@") }, singleLine = true, isError = error != null,
            supportingText = { Text(error ?: "3–20 حرف: a-z وأرقام و _ ويبدأ بحرف") },
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        Button(
            enabled = !busy && name.isNotBlank() && ProfileRepository.validUsername(uname),
            onClick = {
                busy = true
                scope.launch {
                    if (!ProfileRepository.usernameFree(uname)) { error = "اليوزر نيم ده مستخدم"; busy = false; return@launch }
                    val err = session.saveUsername(uname)
                    if (err != null) error = err else session.saveProfile(name.trim(), session.me.value?.about.orEmpty())
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text("يلا نبدأ") }
    }
}
