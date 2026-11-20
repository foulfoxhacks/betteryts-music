import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.foulfoxhacks.betteryts.music"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.foulfoxhacks.betteryts.music"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "0.9.2"
        // Built by ../scripts/build-core.ps1 into src/main/jniLibs.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Release signing lives in android/keystore.properties (gitignored, alongside the .jks).
    val keystoreProperties = Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    if (keystoreProperties.isEmpty()) {
        logger.warn("betteryts: android/keystore.properties not found â€” the release APK will be UNSIGNED")
    }
    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Where the in-app updater looks. Debug builds use a local test server (10.0.2.2 is the
        // host from the emulator) so the update path can be tested without publishing anything.
        debug {
            buildConfigField("String", "UPDATE_MANIFEST", "\"http://10.0.2.2:5175/update.json\"")
            buildConfigField("String", "UPDATE_HOST", "\"10.0.2.2\"")
        }
        release {
            buildConfigField("String", "UPDATE_MANIFEST", "\"https://yts.foulfoxhacks.dev/app/update.json\"")
            buildConfigField("String", "UPDATE_HOST", "\"yts.foulfoxhacks.dev\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Two builds of the same app (same package and key):
    //   sideload: from yts.foulfoxhacks.dev, updates itself (Updater), background play available.
    //   play:     for Google Play, which handles updates and doesn't allow self-updating apps or
    //             background playback of YouTube embeds; both are left out.
    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "true")
            buildConfigField("boolean", "PLAY_STORE", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE", "false")
            buildConfigField("boolean", "PLAY_STORE", "true")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // WebSocket client for Listen Together (BetterYTS watch-party rooms).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Document-start scripts in the player's YouTube frame (background play).
    implementation("androidx.webkit:webkit:1.14.0")
    // Device files: offline playback of music stored on the phone.
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
