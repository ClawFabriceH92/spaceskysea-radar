import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------------
// Signature release (auto-update GitHub Releases) — même pattern que
// network-scanner : keystore en B64 via env (CI) ou fichier local (dev).
// ---------------------------------------------------------------------------
val localKeystore = listOf(
    File("/root/.secrets/keystores-android/spaceskysea-release.keystore"),
    File(System.getProperty("user.home"), ".secrets/keystores-android/spaceskysea-release.keystore")
).firstOrNull { it.exists() }
val secretFiles = listOf(
    File("/root/.secrets/keystores-android/spaceskysea-release.env"),
    File("/root/.secrets/keystores-android/passwords.env"),
    File(System.getProperty("user.home"), ".secrets/keystores-android/spaceskysea-release.env"),
    File(System.getProperty("user.home"), ".secrets/keystores-android/passwords.env")
).filter { it.exists() }

fun secret(envName: String, key: String): String? {
    val fromEnv = System.getenv(envName)
    if (!fromEnv.isNullOrBlank()) return fromEnv
    for (f in secretFiles) {
        if (!f.exists()) continue
        f.readLines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0 && line.substring(0, idx).trim() == key) {
                return line.substring(idx + 1).trim()
            }
        }
    }
    return null
}

val releaseKeystoreB64 = System.getenv("SPACESKYSEA_KEYSTORE_B64")
val releaseStorePassword = secret("SPACESKYSEA_KEYSTORE_PASSWORD", "SPACESKYSEA_KEYSTORE_PASSWORD")
val releaseKeyAlias = secret("SPACESKYSEA_KEY_ALIAS", "SPACESKYSEA_KEY_ALIAS") ?: "spaceskysea"
val releaseKeyPassword = secret("SPACESKYSEA_KEY_PASSWORD", "SPACESKYSEA_KEY_PASSWORD")
    ?: releaseStorePassword

var releaseKeystoreFile: File? = null
if (!releaseKeystoreB64.isNullOrBlank()) {
    val runnerTemp = System.getenv("RUNNER_TEMP") ?: System.getProperty("java.io.tmpdir")
    val f = File(runnerTemp, "spaceskysea-release.keystore")
    f.parentFile?.mkdirs()
    f.writeBytes(Base64.getDecoder().decode(releaseKeystoreB64.trim()))
    releaseKeystoreFile = f
} else if (localKeystore?.exists() == true) {
    releaseKeystoreFile = localKeystore
}

val hasReleaseSigning = releaseKeystoreFile != null && !releaseStorePassword.isNullOrBlank()

android {
    namespace = "com.fabrice.spaceskysea"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fabrice.spaceskysea"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "1.2.6"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // Carte OpenStreetMap (sans Google Maps)
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Caméra (mode Ciel AR)
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Réseau : OpenSky (REST) + AISstream (WebSocket)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
