plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.metrolist.innertube"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.encoding)
    implementation(libs.brotli)
    implementation("com.github.MetrolistGroup:MetrolistExtractor:6305155") {
        exclude(group = "com.google.protobuf")
    }
    // Extraction/cipher/client-fallback engine used by InnerTubeXPlayer.kt (app module).
    // `api` (not `implementation`) so its com.metrolist.innertubex.* classes are visible on
    // :app's compile classpath, same as upstream MetrolistGroup/Metrolist does it.
    api(libs.innertubex)
    implementation(libs.timber)
    testImplementation(libs.junit)

    coreLibraryDesugaring(libs.desugaring)
}
