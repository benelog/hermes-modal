plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.benelog.kakaocollector"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.benelog.kakaocollector"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // 시각 라벨 OCR(카톡이 접근성에 노출하지 않음). 모델 번들형 — 기기 내 실행, 네트워크 불필요.
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    testImplementation("junit:junit:4.13.2")
}
