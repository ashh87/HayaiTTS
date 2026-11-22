// Kokoro English text front end shared by HayaiTTS (:app) and the lab app (:lab): the misaki G2P port,
// markup (docs/MARKUP.md), accent rules and the phoneme frontends, with misaki's data in assets/g2p.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

android {
    namespace = "dev.ahmedmohamed.hayaitts.speech"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    // A library can't bundle a local .aar: each app adds piper-phonemize (espeak-ng) itself.
    compileOnly(files("../libs/piper-phonemize-1.5.9.aar"))

    testImplementation(libs.junit4)
}
