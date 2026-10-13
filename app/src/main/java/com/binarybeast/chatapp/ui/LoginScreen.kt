package com.binarybeast.chatapp.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.sp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** الدخول والتسجيل برقم الهاتف، والتأكيد عن طريق بوت تيليجرام. */
@Composable
fun LoginScreen(vm: LoginVm = viewModel()) {
    val waiting by vm.waiting.collectAsState()
    val error by vm.error.collectAsState()
    val ctx = LocalContext.current

    Box(Modifier.fillMaxSize()) {
    TeamBadge(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp))
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(150.dp).glass(androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(com.binarybeast.chatapp.R.drawable.logo),
                contentDescription = "ATHEER",
                modifier = Modifier.size(104.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text("ATHEER", style = MaterialTheme.typography.displaySmall, fontWeight = androidx.compose.ui.text.font.FontWeight.Black, color = LocalAccent.current, letterSpacing = 6.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (waiting) "افتح تيليجرام، اضغط Start، وبعدين «شارك رقمي».\nهندخلك أوتوماتيك أول ما تأكّد."
            else "هنأكّد رقمك عن طريق تيليجرام، من غير كود SMS ومن غير فلوس.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        if (!waiting) {
            Row(
                Modifier.fillMaxWidth().height(60.dp).glass(androidx.compose.foundation.shape.CircleShape, LocalAccent.current)
                    .clickable { vm.start { url -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AIcons.Send, null, tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(10.dp))
                Text("الدخول عن طريق تيليجرام", color = MaterialTheme.colorScheme.onPrimary, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        } else {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { vm.reopen { url -> ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                Text("افتح تيليجرام تاني")
            }
            TextButton(onClick = { vm.cancel() }) { Text("إلغاء") }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
    }
}
