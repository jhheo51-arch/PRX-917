plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.prx917"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.prx917"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // 공유 소스도 함께 컴파일: 생성된 UniFFI Kotlin 바인딩 + 전송 참고구현
    sourceSets["main"].java.srcDirs(
        "../../../crypto_core/bindings/kotlin",
        "../../../transport/android"
    )
    // 네이티브 .so 는 scripts/build-rust.sh 가 app/src/main/jniLibs/<abi>/ 에 넣는다

    buildTypes {
        getByName("release") { isMinifyEnabled = false }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("com.google.android.gms:play-services-nearby:19.3.0")
    implementation("net.java.dev.jna:jna:5.14.0@aar")   // UniFFI Kotlin 런타임
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")       // QR 생성(페어링)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")  // QR 카메라 스캔
    implementation("androidx.biometric:biometric:1.1.0")            // 생체인증 잠금
    implementation("androidx.fragment:fragment-ktx:1.8.1")          // FragmentActivity(BiometricPrompt)
}
