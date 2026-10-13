import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Firebase (FCM) اختياري: لو google-services.json موجود جوه app/ بيتفعّل، لو لأ التطبيق بيتبني عادي من غير push.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.binarybeast.chatapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.binarybeast.chatapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // القيم بتتقري من: متغيرات البيئة (GitHub Secrets) ← local.properties ← قيمة افتراضية فاضية
        val localProps = Properties()
        rootProject.file("local.properties").let { f ->
            if (f.exists()) f.inputStream().use { localProps.load(it) }
        }
        fun cfg(name: String, default: String = ""): String =
            (System.getenv(name)?.takeIf { it.isNotBlank() } ?: localProps.getProperty(name)?.takeIf { it.isNotBlank() } ?: default).replace("\"", "")

        val supabaseUrl = cfg("SUPABASE_URL", "https://gvrqgcryszvubsuvlgid.supabase.co").trimEnd('/')
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        // مفتاح anon/publishable (مفتاح عام مقصود إنه يتحط في التطبيق؛ الأمان من RLS). مش service_role أبدًا.
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${cfg("SUPABASE_ANON_KEY", "sb_publishable_Dpzy8Uft0Wi0TajzIi4LZg_Ptl0kKHq")}\"")
        // Edge Functions (مفيش توكن بوت ولا أسرار جوه التطبيق)
        buildConfigField("String", "TELEGRAM_PROXY_URL", "\"$supabaseUrl/functions/v1/telegram-storage\"")
        buildConfigField("String", "TG_AUTH_URL", "\"$supabaseUrl/functions/v1/tg-auth\"")
        buildConfigField("String", "PUSH_URL", "\"$supabaseUrl/functions/v1/push\"")
        // STUN عام. TURN اختياري للشبكات الصارمة (CGNAT)
        buildConfigField("String", "TURN_URL", "\"${cfg("TURN_URL")}\"")
        buildConfigField("String", "TURN_USER", "\"${cfg("TURN_USER")}\"")
        buildConfigField("String", "TURN_PASS", "\"${cfg("TURN_PASS")}\"")
    }

    // توقيع الـ release: بيتفعّل بس لو KEYSTORE_PATH موجود (من GitHub Secrets)
    signingConfigs {
        val ks = System.getenv("KEYSTORE_PATH")
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Supabase (auth + postgrest + realtime)
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.1"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.ktor:ktor-client-okhttp:3.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // WebRTC (P2P)
    implementation("io.getstream:stream-webrtc-android:1.2.0")

    // رفع الملفات لتيليجرام عبر الـ proxy
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // تخزين الرسايل محليًا على الجهاز (الشات P2P مفيهوش سيرفر بيحفظ التاريخ)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // محرر الفيديو (قص/ضغط/فلاتر/نص) + مشغّل الفيديو
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    // قراءة اتجاه الصورة (EXIF)
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // تخزين المفتاح الخاص مشفّر على الجهاز
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // إشعارات FCM (مجانية)
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
