# PRX-917

**인터넷·기지국 없이 동작하는 1:1 종단간 암호화 텍스트 무전기.**
오프그리드 무선(LoRa / Wi-Fi Direct / MultipeerConnectivity) 위에 Double Ratchet 보안을 얹었다.
군용 장비를 분석·복제하지 않고, 공개 암호 기술만으로 "도청 불가·변조 불가·기지국 불필요"라는
전술 무전기의 핵심 가치를 재현하는 것이 목표다.

---

## 이름의 의미

| 요소 | 뜻 |
|---|---|
| **PRX** | **P**ortable **R**adio e**X**change — 휴대형 무전 교신기. 군 제식명 `AN/PRC` 계열의 느낌을 살린 오마주이되, 실제 제식명과는 무관한 독자 명칭. |
| **917** | 국내 LoRa **ISM 대역의 시작 주파수 917MHz**. 합법적으로 쓸 수 있는 주파수를 이름에 새겼다. |

> PRC-999K 같은 군용 무전기에서 영감을 받았지만, 그 장비의 주파수 도약·암호 체계를
> 모방하거나 분석하지 않는다. "쓰기 쉽지만 보안이 우수한 무전기"라는 **목표**만 공유한다.

---

## 특징

| | |
|---|---|
| 📡 **오프그리드** | 인터넷·통신사·기지국 없이 기기 간 직접 통신 |
| 🔒 **종단간 암호화** | X25519 · Double Ratchet · ChaCha20-Poly1305 |
| 🔁 **전방향 비밀성** | 메시지마다 키 교체 — 한 번 털려도 과거/미래 안전 |
| 🪪 **중간자 방어** | 안전번호(safety number) 양측 대조 |
| 📻 **무전기 UX** | 채널 선택 · 호출 · 안전번호 확인 |
| 🤝 **크로스플랫폼** | 하나의 Rust 코어를 iOS(Swift)·Android(Kotlin)가 공유 |

---

## 저장소 구조

```
PRX-917/
├─ README.md                 이 문서
├─ LICENSE
├─ docs/
│   ├─ ARCHITECTURE.md        아키텍처 · 위협 모델 · 보안 설계
│   └─ TRANSPORT.md           오프그리드 전송 계층 설계
├─ crypto_core/              Rust 공유 암호 코어 (iOS/Android 공용)
│   ├─ src/
│   │   ├─ lib.rs             신원 · 안전번호 · X3DH-lite · AEAD
│   │   ├─ session.rs         Double Ratchet (vodozemac)
│   │   ├─ api.rs             공개 API + 영구저장(pickle)
│   │   └─ ffi.rs             UniFFI 노출 래퍼
│   └─ bindings/             생성된 Swift·Kotlin 바인딩
├─ transport/
│   ├─ android/NearbyTransport.kt     Nearby Connections (참고 구현)
│   └─ ios/MultipeerTransport.swift   MultipeerConnectivity (참고 구현)
└─ hardware/
    └─ PRX-917-concept.html   러기드 핸드셋 3D/평면도 컨셉
```

---

## 빠른 시작

### 요구 사항 (Windows)
Rust(GNU 툴체인) + mingw-w64. 컴파일 링커(`dlltool`/`gcc`)를 위해 mingw `bin`을 PATH에 넣어야 한다.

```powershell
# 1) Rust (GNU) 설치
winget install Rustlang.Rustup
rustup toolchain install stable-x86_64-pc-windows-gnu
rustup default stable-x86_64-pc-windows-gnu

# 2) mingw-w64 (WinLibs) 설치
winget install BrechtSanders.WinLibs.POSIX.UCRT

# 3) PATH에 mingw bin 추가 (세션마다)
$mingw = "$env:LOCALAPPDATA\Microsoft\WinGet\Packages\BrechtSanders.WinLibs.POSIX.UCRT_Microsoft.Winget.Source_8wekyb3d8bbwe\mingw64\bin"
$env:PATH = "$mingw;$env:PATH"
```

### 빌드 & 테스트
```powershell
cd crypto_core
cargo test
```
현재 **테스트 23건 통과** (lib 8 · session 4 · api 7 · ffi 4).

---

## 사용법 (공개 API)

핵심 흐름 — **수신자가 프리키를 공개**하고, **발신자가 세션을 열어 첫 메시지를 보낸다**.

### Rust
```rust
use crypto_core::api::{Identity, SecureSession};

// 수신자(Bob): 신원 생성 + 프리키 발행
let mut bob = Identity::generate();
let bob_prekey = bob.generate_prekey().unwrap();   // QR 등으로 공개
let bob_id = bob.public_key();

// 발신자(Alice): 세션 시작 + 첫 메시지
let alice = Identity::generate();
let (mut a_sess, wire) = alice
    .start_session(&bob_id, &bob_prekey, "작전 개시".as_bytes())
    .unwrap();                                     // wire → 전송 계층으로

// 수신자: 세션 수락 + 복호화
let (mut b_sess, first) = bob.accept_session(&alice.public_key(), &wire).unwrap();
assert_eq!(first, "작전 개시".as_bytes());

// 중간자 방어: 양측이 같은 안전번호인지 대조
let sn = alice.safety_number_with(&bob.public_key()).unwrap();
```

### Swift (iOS)
```swift
let bob = Identity.generate()
let bobPrekey = try bob.generatePrekey()
let alice = Identity.generate()

let aSess = try alice.openSession(theirIdB64: bob.publicKey(), theirPrekeyB64: bobPrekey)
let wire  = try aSess.encrypt(plaintext: Data("작전 개시".utf8))   // 전송 계층으로

let bSess = try bob.acceptSession(theirIdB64: alice.publicKey(), wire: wire)
let first = bSess.takeFirstMessage()               // "작전 개시"
```

### Kotlin (Android)
```kotlin
val bob = Identity.generate()
val bobPrekey = bob.generatePrekey()
val alice = Identity.generate()

val aSess = alice.openSession(bob.publicKey(), bobPrekey)
val wire  = aSess.encrypt("작전 개시".toByteArray())      // 전송 계층으로

val bSess = bob.acceptSession(alice.publicKey(), wire)
val first = bSess.takeFirstMessage()
```

### 영구저장 (앱 재시작 대비)
```
save(pickleKey) → 암호화 문자열 → Keychain(iOS) / Keystore(Android)에 저장
restore(문자열, pickleKey) → 복원
```

---

## 크로스플랫폼 바인딩 재생성 (UniFFI)
```powershell
cd crypto_core
cargo build
cargo run --bin uniffi-bindgen -- generate --library target\debug\crypto_core.dll --language swift  --out-dir bindings\swift
cargo run --bin uniffi-bindgen -- generate --library target\debug\crypto_core.dll --language kotlin --out-dir bindings\kotlin
```

---

## 전송 계층

`crypto_core`가 만든 **wire 문자열**을 인터넷 없이 폰↔폰 전달한다. 자세한 설계는 [`docs/TRANSPORT.md`](docs/TRANSPORT.md).

- **채널 = 주파수 선택**: `prx917.chN`(Android) / `prx917-chN`(iOS) 식별자로 전환
- **프레임 태그**: `K`(프리키) · `P`(PreKey wire) · `N`(일반 wire)
- 전송 경로가 도청돼도 내용은 Double Ratchet로 보호 — 전송 계층은 바이트 왕복만 담당

> 전송 참고 구현은 Android/iOS SDK가 필요해 이 저장소에선 **컴파일하지 않았다**.
> 실제 앱 프로젝트(Android Studio / Xcode)에 넣고 권한·수명주기를 붙여야 동작한다.

---

## 하드웨어 컨셉

러기드 핸드셋의 3D 뷰·전면 평면도·사양을 [`hardware/PRX-917-concept.html`](hardware/PRX-917-concept.html)에서 볼 수 있다
(브라우저로 열기). 소프트웨어 중심 프로젝트이므로 하드웨어는 개념 시각화 단계다.

---

## 로드맵

- [x] 보안 코어 (신원 · Double Ratchet · AEAD · 저장) — 테스트 23
- [x] UniFFI 바인딩 (Swift / Kotlin)
- [x] 전송 계층 설계 + 참고 구현
- [x] 하드웨어 컨셉 (3D/평면도)
- [ ] 앱 UI (채널 다이얼 · 안전번호 · 채팅)
- [ ] OS별 네이티브 라이브러리 빌드 (iOS=macOS/Xcode, Android=NDK)
- [ ] 실기기 통합 테스트

---

## 보안 · 법적 고지

- **설계 원칙**: 핵심 암호는 직접 구현하지 않고 감사 완료된 라이브러리(vodozemac)를 사용한다.
  직접 작성한 코드는 조립·검증·저장에 한정한다.
- **주파수 규정**: 국내 LoRa는 917–923.5MHz ISM 대역·출력·점유시간(duty cycle) 규정을 준수해야 한다.
- **범위**: 군용 장비의 분석·복제·주파수 도약 모방은 하지 않는다. 공개 표준·오픈소스 암호만 사용한다.
- 이 저장소에는 개인키 등 비밀값이 포함되어서는 안 된다(암호화 pickle은 OS 보안저장소에 보관).

## 라이선스

MIT License — [`LICENSE`](LICENSE) 참고.
