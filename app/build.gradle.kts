import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

// Release signing key, outside git: local.properties points to the keystore (see docs/DEVELOPMENT.md).
// Without it (a fresh checkout), release builds fall back to the debug key.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseStore: String? = localProps.getProperty("harbor.signing.storeFile")

android {
    namespace = "com.synrg.harbor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.synrg.harbor"
        minSdk = 29
        targetSdk = 36
        // -Pharbor.versionCode/-Pharbor.versionName build an "older" copy to test the in-app updater.
        versionCode = providers.gradleProperty("harbor.versionCode").orNull?.toInt() ?: 12
        versionName = providers.gradleProperty("harbor.versionName").orNull ?: "0.8.0"
        // In-app updates from GitHub Releases; a store build (F-Droid) would turn this off.
        buildConfigField("boolean", "SELF_UPDATE", "true")
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = localProps.getProperty("harbor.signing.storePassword")
                keyAlias = localProps.getProperty("harbor.signing.keyAlias")
                keyPassword = localProps.getProperty("harbor.signing.keyPassword")
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // English by default, Spanish and Portuguese for phones in those; drops other languages bundled by libraries.
    androidResources {
        localeFilters += setOf("en", "es", "pt")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.media3.common.util.UnstableApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

baselineProfile {
    // Regenerate with scripts/baseline-profile.ps1; the result is committed under src/release/generated.
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
}

dependencies {
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ffmpeg)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.haze)
    // Chromecast. Started only when the user opens "Play on a TV" (cast/GoogleCast.kt).
    implementation(libs.play.services.cast.framework)

    testImplementation(libs.junit)
}
