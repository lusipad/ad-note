plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.adnote"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adnote"
        minSdk = 26
        // 自用侧载：targetSdk 低一些，隐藏 API 限制更宽松，Onyx SDK 更稳
        targetSdk = 30
        versionCode = 9
        versionName = "0.3.1"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 自用：release 也用 debug 签名，方便直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { viewBinding = true; buildConfig = true }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/license*",
                "META-INF/NOTICE*", "META-INF/notice*", "META-INF/ASL2.0",
                "META-INF/*.kotlin_module",
            )
        }
        jniLibs {
            pickFirsts += "lib/*/libc++_shared.so"
            excludes += "lib/*/libc++.so"
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 文石 Onyx SDK（版本与官方 OnyxAndroidDemo 一致）
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.5.4.4")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    // 手写识别
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
