# PRX-917

**PRX** = Portable Radio eXchange · **917** = 한국 LoRa ISM 대역 시작 주파수(917MHz)

오프그리드 1:1 종단간 암호화 텍스트 메신저 — 공유 암호 코어.
인터넷·기지국 없이 두 기기가 안전하게 문자 통신하는 것을 목표로 한다.
(군용 장비를 분석·복제하지 않고, 공개 암호 기술로 동일한 보안 목표를 달성한다.)

## 구조

```
prx-917/
└─ crypto_core/            iOS/Android 공유 Rust 암호 코어
   └─ src/
      ├─ lib.rs      신원키 생성 · 안전번호 · X3DH-lite · AEAD 포장
      ├─ session.rs  Double Ratchet 세션 (vodozemac)
      └─ api.rs      앱이 호출하는 공개 API + 영구저장(pickle)
```

## 보안 속성

| 속성 | 수단 |
|---|---|
| 기밀성·무결성·인증 | ChaCha20-Poly1305 (AEAD) |
| 전방향 비밀성·침해후 회복성 | Double Ratchet (vodozemac, 감사 완료) |
| 중간자(MITM) 저항 | 안전번호(safety number) 대조 |
| 저장 시 보호 | 암호화 pickle → OS 보안저장소(Keychain/Keystore) |

핵심 암호는 감사된 라이브러리에 위임하고, 직접 작성한 코드는 조립·검증·저장에 한정한다.

## 앱 사용 흐름 (api.rs)

```
[수신자] Identity::generate() → public_key(), generate_prekey() 를 QR로 공개
[발신자] Identity::start_session(상대 신원키, 상대 프리키, 첫 메시지) → (세션, wire)
[수신자] Identity::accept_session(상대 신원키, wire) → (세션, 평문)
[양측]   SecureSession::encrypt/decrypt 로 이후 대화
         save()/restore() 로 앱 재시작 대비
전송 계층(Wi-Fi Direct/BLE)은 wire 문자열만 그대로 실어 나른다.
```

## 빌드 / 테스트 (Windows)

GNU 툴체인 + mingw-w64(WinLibs) 사용. mingw `bin`을 PATH에 넣어야 한다.

```powershell
$mingw = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\mingw64\bin"
$env:PATH = "$mingw;$env:PATH"
cd prx-917\crypto_core
cargo test
```

## 크로스플랫폼 바인딩 (UniFFI)

검증된 코어를 Swift(iOS)·Kotlin(Android)에서 네이티브 객체처럼 호출한다.
FFI 전용 래퍼는 `src/ffi.rs`, 생성물은 `bindings/` 에 있다.

재생성:
```powershell
cargo build
cargo run --bin uniffi-bindgen -- generate --library target\debug\crypto_core.dll --language swift  --out-dir bindings\swift
cargo run --bin uniffi-bindgen -- generate --library target\debug\crypto_core.dll --language kotlin --out-dir bindings\kotlin
```

생성물:
- `bindings/swift/`  — `crypto_core.swift`, `crypto_coreFFI.h`, `crypto_coreFFI.modulemap`
- `bindings/kotlin/uniffi/crypto_core/crypto_core.kt`

Swift 사용 예:
```swift
let bob = Identity.generate()
let bobPrekey = try bob.generatePrekey()
let alice = Identity.generate()

let aSess = try alice.openSession(theirIdB64: bob.publicKey(), theirPrekeyB64: bobPrekey)
let wire  = try aSess.encrypt(plaintext: Data("작전 개시".utf8))   // 전송 계층으로

let bSess = try bob.acceptSession(theirIdB64: alice.publicKey(), wire: wire)
let first = bSess.takeFirstMessage()   // "작전 개시"
```

> **네이티브 라이브러리 빌드**는 각 OS 타깃이 필요하다.
> iOS: macOS + Xcode에서 `aarch64-apple-ios` 등으로 cross-compile.
> Android: NDK + `aarch64-linux-android` 등 타깃. (이 Windows 환경에선 바인딩 코드 생성까지만 가능.)

## 다음 단계

- [x] UniFFI 바인딩 생성 (Swift / Kotlin)
- [x] 하드웨어 컨셉 (러기드 핸드셋 3D/평면도)
- [x] 전송 계층 설계 + 참고 구현 (`transport/`)
- [ ] OS별 네이티브 라이브러리 빌드 (iOS=macOS, Android=NDK)
- [ ] 실기기 통합 테스트 (바이트 왕복)

### 전송 계층 (`transport/`)

오프그리드로 `wire` 문자열을 폰↔폰 전달. 설계는 `transport/TRANSPORT.md`.

- `transport/android/NearbyTransport.kt` — Nearby Connections (참고 구현)
- `transport/ios/MultipeerTransport.swift` — MultipeerConnectivity (참고 구현)

채널(= 주파수 선택)·안전번호 대조·프레임 태그(K/P/N) 핸드셰이크를 포함한다.
