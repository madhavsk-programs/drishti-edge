import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val drishtiLocalProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { input -> load(input) }
}

android {
    namespace = "com.drishti.dashboard"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.drishti.dashboard"
        // Lower than the walking app's 31. Nothing here touches the NPU, the
        // camera or a foreground service, and a coordinator's phone is
        // whatever the office already owns.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        buildConfig = true
        aidl = false
        shaders = false
    }

    defaultConfig {
        val coordinatorUrl = providers.gradleProperty("drishtiCoordinatorUrl")
            .orElse("http://172.26.252.170:8000/")
            .get()
        buildConfigField("String", "COORDINATOR_URL", "\"$coordinatorUrl\"")
        buildConfigField(
            "String",
            "MONITOR_TOKEN",
            "\"${drishtiLocalProperties.getProperty("drishti.monitorToken", "configure-me")}\"",
        )
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.core)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    // Only the core icon set: it carries every glyph this app uses and keeps
    // the extended set's several thousand unused vectors out of the APK.
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
