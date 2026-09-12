plugins {
  alias(libs.plugins.android.application)
}

/**
 * P0.8 NPU probe. Deliberately separate from `:app` so that a broken experiment
 * here can never break the product build, and so it compiles in seconds.
 *
 * It answers one question: does the QNN HTP backend actually execute our models
 * on this device, with evidence (BUILD_PLAN.md §6.2).
 */
android {
    namespace = "com.drishti.probe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.drishti.probe"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // The QNN backend libraries and the ORT AAR are arm64 only.
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        debug { isDebuggable = true }
    }
    // QNN's native code dlopen()s "libQnnHtp.so" etc. by bare name, which only
    // resolves if the .so is a real file under nativeLibraryDir. AGP's default
    // page-aligned-in-APK packaging never extracts it there.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Pinned: the Qualcomm SegFormer assets declare ONNX Runtime 1.27.1, and a
    // newer ORT loads an older EPContext model while the reverse is not true.
    // NOTE: this AAR ships NO QNN backend libraries - see BUILD_PLAN.md §3.4.
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")
}
