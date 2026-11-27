import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Harbor without Android: Stremio (account, addons, sync), metadata (TMDB, Wikidata), progress,
// settings and lists, subtitles, and the DLNA side of "Play on a TV". Plain Kotlin on the JVM, so
// any app built on the JVM (the Android app today) uses it as is. No Android imports here.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    // Only Compose's @Immutable (no runtime), so the apps' Compose compiler treats the models as stable.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime.annotation)

    testImplementation(libs.junit)
}
