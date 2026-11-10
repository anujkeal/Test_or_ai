plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.keal.nexus"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.keal.nexus"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.8.1"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17"; arguments += "-DCMAKE_BUILD_TYPE=Release" } }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    ndkVersion = "29.0.13113456"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            // GGML CPU backend plugins are loaded with dlopen() from nativeLibraryDir.
            // Extract them as real files instead of leaving them compressed inside the APK.
            useLegacyPackaging = true
        }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    debugImplementation(libs.androidx.compose.ui.tooling)
}
