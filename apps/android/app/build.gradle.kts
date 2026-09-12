plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.drishti.app"
    compileSdk = 36
    // Pinned: the Scene VLM's llama.cpp build was measured against this NDK.
    // Without it AGP silently picks whichever NDK is installed.
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "com.drishti.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The ONNX Runtime QNN AAR and the QAIRT backend libraries are arm64 only.
        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                // Scene Mode's llama.cpp bridge. The flags live in
                // app/src/main/cpp/CMakeLists.txt because they were measured on
                // device — see docs/SCENE_MODE_VLM.md §4.1.
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += "-O3"
            }
        }
    }

    // Absent until scripts/bootstrap_llama.sh has run. Gate on that rather than
    // failing every build for people who are not touching Scene Mode.
    if (file("../../../third_party/llama.cpp/CMakeLists.txt").exists()) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.31.6"
            }
        }
    } else {
        logger.lifecycle(
            "third_party/llama.cpp absent — building without the Scene VLM. " +
                "Run scripts/bootstrap_llama.sh to enable it."
        )
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }
    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
      // QNN's native code dlopen()s "libQnnHtp.so" by bare name, which only
      // resolves if the .so is a real file under nativeLibraryDir. AGP's
      // default page-aligned-in-APK packaging never extracts it there, and the
      // failure is a silent CPU fallback rather than a link error.
      jniLibs {
        useLegacyPackaging = true
        // The ORT QNN AAR ships a DSP Skel for every Hexagon generation, ~12 MB
        // each. The target is HTP v81 (Snapdragon 8 Elite Gen 5 / 8 Gen 5), so
        // the rest are dead weight in the APK and in install time. Widen this
        // list if the build ever has to run on older silicon.
        excludes += setOf(
          "**/libQnnHtpV68Skel.so",
          "**/libQnnHtpV69Skel.so",
          "**/libQnnHtpV73Skel.so",
          "**/libQnnHtpV75Skel.so",
          "**/libQnnHtpV79Skel.so",
          "**/libQnnDspV66Skel.so",
        )
      }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.activity.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // Camera
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)

  // Networking + serialization
  implementation(libs.retrofit.core)
  implementation(libs.retrofit.serialization)
  implementation(libs.okhttp.core)
  implementation(libs.okhttp.logging)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.coroutines.android)

  // Bundled Latin-script OCR. The model is packaged in the APK, so Explore
  // Mode is available immediately and never downloads or uploads anything.
  // ML Kit runs on-device on CPU; it is deliberately outside the NPU claim.
  implementation("com.google.mlkit:text-recognition:16.0.1")

  // On-device inference. Pinned: the Qualcomm SegFormer assets declare ONNX
  // Runtime 1.27.1, and a newer ORT loads an older EPContext model while the
  // reverse is not true. Its qnn-runtime dependency supplies the compatible
  // backend libraries described in BUILD_PLAN.md §3.4.
  implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")

  // Preferences
  implementation(libs.androidx.datastore.preferences)

  // Unit tests
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
