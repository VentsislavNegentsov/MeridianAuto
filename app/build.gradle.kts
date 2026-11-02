// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.offthegrid"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.offthegrid"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // 16 KB page-size friendly packaging: keep native libs uncompressed (AGP default)
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Map (13.x ships 16 KB-aligned native libraries)
    implementation("org.maplibre.gl:android-sdk:13.6.1")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")

    // Fixes libandroidx.graphics.path.so alignment warning
    implementation("androidx.graphics:graphics-path:1.0.1")

    implementation("androidx.core:core-ktx:1.15.0")

    // Android Auto (Car App Library)
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
}