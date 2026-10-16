# PRX-917 — Android 앱

Jetpack Compose 앱. `crypto_core`(Rust)를 `.so`로 링크하고, Nearby Connections로
오프그리드 1:1 교신한다. **이 앱은 Windows에서 빌드 가능**하고, APK는 안드로이드 폰에
사이드로드하면 된다(Apple 서명 불필요).

## 구성
```
apps/android/
├─ settings.gradle.kts · build.gradle.kts · gradle.properties
├─ local.properties            SDK 경로 (로컬 전용, git 제외)
├─ scripts/build-rust.sh       crypto_core → jniLibs/*.so
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml · res/values/themes.xml
      ├─ java/app/prx917/MainActivity.kt   Compose UI
      ├─ java/app/prx917/RadioViewModel.kt crypto_core + 전송 글루
      └─ jniLibs/<abi>/libcrypto_core.so   (build-rust.sh 생성)
```
공유 소스도 함께 컴파일: `crypto_core/bindings/kotlin`(UniFFI 바인딩), `transport/android`(전송).

## 빌드 (Windows / macOS / Linux)

### 1) 선행 설치
- JDK 17, Android SDK(platform 34, build-tools 34, NDK 26), Rust + android 타깃, cargo-ndk

### 2) 네이티브 라이브러리
```bash
export ANDROID_NDK_HOME=<SDK>/ndk/26.1.10909125
bash apps/android/scripts/build-rust.sh      # jniLibs/*.so 생성
```

### 3) APK 빌드
```bash
cd apps/android
./gradlew assembleDebug
# 산출물: app/build/outputs/apk/debug/app-debug.apk
```

### 4) 설치 (사이드로드)
- USB 연결 후: `adb install app/build/outputs/apk/debug/app-debug.apk`
- 또는 APK를 폰으로 옮겨 "출처를 알 수 없는 앱 설치" 허용 후 실행

## 테스트
"두 폰 교신"을 보려면 **안드로이드 기기 2대**(또는 1대 + 에뮬레이터)에 설치하고,
같은 채널을 선택하면 서로를 발견해 세션을 맺는다. 첫 연결 시 **안전번호**를 양 기기에서 대조한다.

> 참고: Nearby Connections는 Google Play 서비스가 필요하다(대부분의 안드로이드 폰에 기본 탑재).
