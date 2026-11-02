plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pindrop.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pindrop.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    // One fixed signing key, stored in the project. Every build is signed the same way, so a new APK
    // installs OVER the old one and keeps your pairings. (Keep this repository private.)
    signingConfigs {
        create("pindrop") {
            storeFile = file("pindrop.jks")
            storeType = "pkcs12"
            storePassword = "pindrop-app"
            keyAlias = "pindrop"
            keyPassword = "pindrop-app"
        }
    }

    buildTypes {
        // The GitHub build makes the "debug" APK. Normally that one is slow and jerky.
        // These settings make it a fast, smaller build while keeping the same install steps.
        debug {
            signingConfig = signingConfigs.getByName("pindrop")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            signingConfig = signingConfigs.getByName("pindrop")
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
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
