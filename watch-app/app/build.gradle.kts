plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linewatch.watch"
    compileSdk = 36   // v1.0.3：Pulsar 的 androidx.core 1.17 需要

    defaultConfig {
        applicationId = "com.linewatch.watch"
        minSdk = 30
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"
    }

    buildTypes {
        release {
            // 發布用 debug 簽章（免設定 keystore，可直接安裝；本專案無上架商店需求）
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // v1.0.3：Pulsar 震動模式庫（com.swmansion:pulsar 1.3.0，MIT）——依賴庫要求，D6「純 AOSP」部分鬆綁（見 decisions D21）
    implementation("com.swmansion:pulsar:1.3.0")
}
