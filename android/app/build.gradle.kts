import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val defaultChatApiBase = "https://REPLACE_WITH_WORKER.workers.dev"
val chatApiBase = providers.gradleProperty("chatApiBase").orElse(defaultChatApiBase).get().trim().trimEnd('/')
val chatApiBaseIsUsable = runCatching {
    val uri = URI(chatApiBase)
    val host = uri.host.orEmpty()
    uri.scheme.equals("https", ignoreCase = true) && host.isNotBlank() &&
        (uri.path.isNullOrEmpty() || uri.path == "/") &&
        uri.query == null && uri.fragment == null &&
        !host.contains("REPLACE_WITH", ignoreCase = true) &&
        !host.contains("YOUR_WORKER", ignoreCase = true)
}.getOrDefault(false)
val escapedChatApiBase = chatApiBase.replace("\\", "\\\\").replace("\"", "\\\"")

val validateReleaseChatApiBase by tasks.registering {
    doLast {
        if (!chatApiBaseIsUsable) {
            throw GradleException("Release APK requires a real HTTPS Worker URL. Set -PchatApiBase=https://<worker>.workers.dev; do not use the placeholder.")
        }
    }
}

tasks.configureEach {
    if (name.contains("Release", ignoreCase = true) &&
        (name.startsWith("assemble", ignoreCase = true) || name.startsWith("bundle", ignoreCase = true) || name.startsWith("package", ignoreCase = true))) {
        dependsOn(validateReleaseChatApiBase)
    }
}

android {
    namespace = "app.ephemeralchat"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.ephemeralchat"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "API_BASE", "\"$escapedChatApiBase\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Keep dependencies compatible with AGP 8.13 / compileSdk 36; the 2026 BOM
    // selects artifacts that require AGP 9.1 and API 37.
    val composeBom = platform("androidx.compose:compose-bom:2025.08.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:5.0.0")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
