package com.binarybeast.chatapp.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

object Splash { var done = false }

/** "from BINARY Beast" — اسم الفريق. */
@Composable
fun TeamBadge(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("from", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("BINARY Beast", fontSize = 19.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, color = LocalAccent.current)
    }
}

/** شاشة البداية (زي واتساب): اللوجو في النص، واسم الفريق تحت. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        a.animateTo(1f, tween(650))
        delay(900)
        Splash.done = true
        onDone()
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.Center).alpha(a.value).scale(0.85f + 0.15f * a.value).size(150.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
            Image(
                painter = androidx.compose.ui.res.painterResource(com.binarybeast.chatapp.R.drawable.logo),
                contentDescription = "ATHEER", modifier = Modifier.size(104.dp),
            )
        }
        TeamBadge(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 36.dp).alpha(a.value))
    }
}
