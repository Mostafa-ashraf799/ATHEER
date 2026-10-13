package com.binarybeast.chatapp

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.view.WindowManager
import com.binarybeast.chatapp.ui.AppRoot
import com.binarybeast.chatapp.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onStart() { super.onStart(); com.binarybeast.chatapp.ui.AppLock.onStart() }
    override fun onStop() { super.onStop(); com.binarybeast.chatapp.ui.AppLock.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val permLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { }
            LaunchedEffect(Unit) {
                permLauncher.launch(
                    arrayOf(
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS,
                    )
                )
            }
            // منع لقطات الشاشة لو الإعداد شغّال
            val prefs by com.binarybeast.chatapp.data.AppSettings.prefs.collectAsState()
            LaunchedEffect(prefs.screenSecurity) {
                if (prefs.screenSecurity) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            AppTheme {
                val locked by com.binarybeast.chatapp.ui.AppLock.locked.collectAsState()
                val callState by com.binarybeast.chatapp.call.CallManager.state.collectAsState()
                // مكالمة شغّالة أو بترن: متتحجبش ورا القفل
                var splash by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(!com.binarybeast.chatapp.ui.Splash.done) }
                if (splash) com.binarybeast.chatapp.ui.SplashScreen { splash = false }
                else if (locked && callState == com.binarybeast.chatapp.call.CallState.Idle) com.binarybeast.chatapp.ui.PinScreen()
                else AppRoot()
            }
        }
    }
}
