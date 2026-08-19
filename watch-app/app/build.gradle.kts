plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linewatch.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.linewatch.watch"
        minSdk = 30
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
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
    // 純 AOSP（docs/decisions.md D6）：不引入 androidx 與 GMS
}
