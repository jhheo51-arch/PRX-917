# PRX-917 아키텍처

오프그리드 1:1 종단간 암호화 텍스트 무전기. 인터넷·기지국 없이 두 기기가
안전하게 문자 통신한다. 군용 장비(PRC-999K 등)를 분석·복제하지 않고,
공개 암호 기술로 동일한 보안 목표를 달성한다.

## 1. 목표

| 목표 | 실현 |
|---|---|
| 인터넷 없이 통신 | LoRa(하드웨어) / Wi-Fi Direct·MultipeerConnectivity(앱) |
| 도청 불가 | ChaCha20-Poly1305 종단간 암호화 |
| 변조 불가 | AEAD 무결성 태그 |
| 과거 메시지 보호 | Double Ratchet 전방향 비밀성 |
| 사칭 방어 | 안전번호(safety number) 대조 |
| 쉬운 사용 | 무전기 감성 UX(채널·호출·안전번호) |

## 2. 위협 모델

**막는 것**
- 수동 도청(전파 가로채기) → 암호문만 노출
- 능동 변조/재전송 → AEAD·래칫 카운터로 거부
- 중간자(MITM) → 안전번호 대조로 차단
- 기기 분실 시 과거 세션 → 전방향 비밀성 + 암호화 pickle 저장

**막지 않는 것(명시)**
- 송수신 "사실" 자체(메타데이터) — 전파가 오간다는 정황은 숨기지 않음
- 침해된 기기의 실시간 화면 — 엔드포인트 보안은 OS 몫
- 전파 방해(jamming) — 가용성은 물리 계층 한계

## 3. 계층 구조

```
┌───────────────────────────────────────────┐
│ 앱 UI (채널 다이얼 · 안전번호 · 채팅)          │
├───────────────────────────────────────────┤
│ crypto_core  (Rust, iOS/Android 공유)        │
│   lib.rs      신원 · 안전번호 · AEAD           │
│   session.rs  Double Ratchet (vodozemac)      │
│   api.rs      공개 API + 영구저장(pickle)       │
│   ffi.rs      UniFFI 노출 래퍼                 │
├───────────────────────────────────────────┤
│ 바인딩  bindings/{swift,kotlin}               │
├───────────────────────────────────────────┤
│ 전송 계층  transport/                         │
│   android  Nearby Connections                 │
│   ios      MultipeerConnectivity              │
└───────────────────────────────────────────┘
```

## 4. 보안 설계

- **신원키(IK)**: 장기 X25519 키쌍, 기기당 1개. 비밀키는 Secure Enclave/Keystore에 봉인.
- **세션 수립**: X3DH로 루트키 합의 → Double Ratchet 시작. (vodozemac Olm에 위임, 감사 완료)
- **메시지**: 래칫이 메시지마다 새 키 발급 → ChaCha20-Poly1305 AEAD.
- **안전번호**: 두 신원 공개키를 정렬·해시 → 5자리 3블록. 양측 동일 시 중간자 없음.
- **저장**: Account/Session을 암호화 pickle 문자열로 봉인 → OS 보안저장소에 보관(앱 재시작 대비).
- **원칙**: 핵심 암호는 직접 짜지 않고 감사된 라이브러리(vodozemac) 사용. 직접 코드는 조립·검증·저장에 한정.

## 5. 데이터 흐름

```
평문 ──encrypt()──▶ wire 문자열("P…"/"N…") ──전송계층──▶ 상대 기기
                                                          │
평문 ◀──decrypt()── wire 문자열 ◀──────────────────────────┘
```

전송 프레임 태그: `K`=프리키 공개 · `P`=PreKey wire · `N`=일반 wire.
전송 경로가 도청돼도 내용은 래칫으로 보호되므로, 전송 계층은 "바이트 왕복"만 책임진다.

## 6. 채널 모델 (PRC-999K 매핑)

| PRC-999K | PRX-917 |
|---|---|
| 주파수/채널 선택 | 네트워크 식별자 `prx917.chN` / `prx917-chN` 전환 |
| COMSEC 키 적재 | QR/프리키 교환 + 안전번호 확인 |
| 네트 진입 | 피어 발견 → 연결 |

## 7. 모듈 맵

| 경로 | 역할 |
|---|---|
| `crypto_core/src/lib.rs` | 신원·안전번호·X3DH-lite·AEAD |
| `crypto_core/src/session.rs` | Double Ratchet 세션 |
| `crypto_core/src/api.rs` | 공개 API + 저장/복원 |
| `crypto_core/src/ffi.rs` | UniFFI 래퍼(CoreError·Mutex·Vec<u8>) |
| `crypto_core/bindings/` | Swift·Kotlin 생성물 |
| `transport/` | 오프그리드 전송 설계 + 참고 구현 |
| `concept/PRX-917-concept.html` | 하드웨어 3D/평면도 컨셉 |

## 8. 테스트 현황

`cargo test` — **23건 통과** (lib 8 · session 4 · api 7 · ffi 4).
핵심 검증: X3DH 양측 루트키 일치, AEAD 변조/오키 거부, 안전번호 일치,
앱 재시작(세션 pickle 복원), FFI 전체 흐름.

## 9. 빌드 / 툴체인

- Rust (GNU 툴체인) + mingw-w64(WinLibs). 상세는 `README.md`.
- UniFFI 바인딩 재생성 명령도 `README.md` 참고.

## 10. 한계 & 로드맵

- 이 저장소는 **보안 코어 + 바인딩 + 전송 설계**까지. 실제 앱 UI와 OS별
  네이티브 라이브러리 빌드(iOS=macOS/Xcode, Android=NDK)는 각 플랫폼 환경 필요.
- 전송 참고 구현은 미컴파일(SDK 부재). 실기기 바이트 왕복 검증이 다음 단계.

## 11. 법적·윤리 주석

- 국내 LoRa는 917–923.5MHz ISM 대역·출력·점유시간 규정을 준수해야 한다.
- 군용 장비의 분석·복제·주파수 도약 모방은 하지 않는다. 본 프로젝트는 공개
  표준·오픈소스 암호만 사용한다.
