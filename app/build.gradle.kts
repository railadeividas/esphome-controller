plugins {
    id("com.google.protobuf")
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Version: CI passes -PappVersionName/-PappVersionCode/-PappBuildTime (see .github/workflows/release.yml).
// Local builds fall back to git (commit count and `git describe`), then to fixed defaults.
fun git(vararg args: String): String? = runCatching {
    providers.exec { commandLine("git", *args); isIgnoreExitValue = true }.standardOutput.asText.get().trim().ifEmpty { null }
}.getOrNull()
val gitCommit = git("rev-parse", "--short=12", "HEAD") ?: "unknown"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt()
    ?: git("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1
val appVersionName = (findProperty("appVersionName") as String?)
    ?: git("describe", "--tags", "--match", "v[0-9]*", "--dirty")?.removePrefix("v")
    ?: "0.0.0-dev+$gitCommit"
// Build time (epoch milliseconds): CI passes -PappBuildTime; local builds use the current time.
val appBuildTime = (findProperty("appBuildTime") as String?)?.toLongOrNull() ?: System.currentTimeMillis()

// Release signing comes from the environment (CI secrets); without it, release builds use the debug key.
val releaseKeystore = System.getenv("ANDROID_KEYSTORE_PATH")?.let(::file)?.takeIf { it.exists() }

android {
    namespace = "com.example.esphomecontroller"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.example.esphomecontroller"; minSdk = 26; targetSdk = 35
        versionCode = appVersionCode; versionName = appVersionName
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("long", "BUILD_TIME", "${appBuildTime}L")
    }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.5" }
    generateProtoTasks { all().configureEach { builtins { create("java") } } }
}

dependencies {
    implementation("io.github.rburgst:okhttp-digest:3.1.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("com.stonygrey:torann:0.3.2")
    implementation("com.google.protobuf:protobuf-java:3.25.5")
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
