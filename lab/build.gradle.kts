// Kokoro Lab: listening tools for the accent voice pack (kokoro tools ROADMAP 6b). Runs the shipping
// model.onnx with ONNX Runtime (so voices can be mixed live) and fetches its parts from lab_server.py.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

android {
    namespace = "dev.ahmedmohamed.hayaitts.lab"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ahmedmohamed.hayaitts.lab"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    androidResources {
        // misaki tables (from :speech) are memory-mapped straight out of the APK (PackedTable.asset).
        noCompress += "kv"
    }
}

dependencies {
    implementation(project(":speech"))
    implementation(files("../libs/piper-phonemize-1.5.9.aar"))
    implementation(libs.onnxruntime.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.workmanager)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit4)
    testImplementation(libs.onnxruntime.jvm)
}
