# PRX-917 전송 계층 설계

`crypto_core`가 만든 **wire 문자열**을 인터넷·기지국 없이 두 폰 사이로 실어 나르는 계층.
암호는 이미 끝났으므로 전송 계층은 "바이트를 전달"하기만 하면 된다 — 전송 경로가 도청돼도
내용은 Double Ratchet로 보호된다.

```
앱 UI
  │  평문 ↔ wire 문자열
crypto_core (Identity / SecureSession)   ← 검증 완료
  │  wire 프레임
전송 계층  (이 문서)
  │  바이트
┌──────────────┬───────────────┐
Android        iOS
Nearby         MultipeerConnectivity
Connections    (MCSession)
```

## 플랫폼 선택

| 플랫폼 | 기술 | 이유 |
|---|---|---|
| Android | **Nearby Connections** (`P2P_POINT_TO_POINT`) | Wi-Fi/BT/핫스팟을 자동 선택, 완전 오프라인, 1:1에 최적 |
| iOS | **MultipeerConnectivity** (`MCSession`) | OS 내장 P2P, 인터넷 불필요, Wi-Fi+BT 혼용 |

두 기술 모두 **메시지 경계를 보존**하므로 wire 문자열을 그대로 한 프레임에 담는다.

## "채널" = PRC-999K 주파수 선택의 소프트웨어 대체

군용 무전기의 주파수/채널을 폰에선 **서비스 식별자**로 재현한다. 같은 채널끼리만 서로를 발견한다.

| 채널 | Android `SERVICE_ID` | iOS `serviceType` |
|---|---|---|
| CH 1 | `prx917.ch1` | `prx917-ch1` |
| CH N | `prx917.chN` | `prx917-chN` (1–15자, 소문자·숫자·`-`) |

채널 변경 = 광고/탐색을 새 식별자로 재시작. 사용자에겐 "채널 노브를 돌리는" 경험.

## 프레임 포맷

wire 문자열은 이미 `P…`(PreKey) / `N…`(일반)로 시작한다. 여기에 제어 프레임 태그 `K`만 더한다.

| 첫 글자 | 종류 | 페이로드 | 처리 |
|---|---|---|---|
| `K` | 프리키 공개 | 프리키 base64 | 수신 측이 보관 → 세션 시작에 사용 |
| `P` | 암호 wire (PreKey) | crypto_core wire 그대로 | `acceptSession()` |
| `N` | 암호 wire (일반) | crypto_core wire 그대로 | `SecureSession.decrypt()` |

신원 공개키는 발견 단계의 메타데이터로 교환한다 (안전번호 계산용):
- Android: 엔드포인트 이름에 `"<표시이름>|<신원키 base64>"`
- iOS: `discoveryInfo = ["id": "<신원키 base64>"]`

## 교신 시퀀스 (1:1)

```
양측: 채널 C 선택 → 광고 + 탐색 시작 (신원 공개키를 메타데이터로 노출)
  │
발견 → 전송 링크 연결 (Nearby requestConnection / Multipeer invitePeer)
  │
안전번호 확인: 두 신원키로 safetyNumberWith() 계산 → 양 화면 대조 → 사용자 승인
  │                                             (최초 1회, 이후 TOFU)
수신자 → 발신자:  "K<프리키>"            (generatePrekey)
발신자:          openSession(수신자 신원키, 프리키)
발신자 → 수신자:  첫 메시지 암호화 → "P…"  (encrypt)
수신자:          acceptSession(발신자 신원키, "P…") → takeFirstMessage()
  │
이후 양방향:     encrypt() → "N…" 전송 → decrypt()
```

## 공통 계약 (양 플랫폼이 동일 구현)

```
interface RadioTransport {
    fun setChannel(n: Int)               // 채널 선택(식별자 전환)
    fun start(myIdentityPubB64: String)  // 광고 + 탐색
    fun stop()
    fun send(frame: ByteArray)           // 프레임 전송(연결된 1 피어)

    // 콜백
    onPeerFound(peerId, theirIdentityPubB64)   // 안전번호 계산 가능 시점
    onConnected(peerId)
    onFrameReceived(peerId, frame)
    onDisconnected(peerId)
}
```

앱 계층은 이 계약 + `crypto_core`만 알면 되고, OS별 차이는 각 구현이 흡수한다.

## 이 환경의 한계

Android/iOS SDK가 없어 **참고 구현은 컴파일하지 않았다**. 실제 앱 프로젝트
(Android Studio / Xcode)에 넣고 권한·수명주기를 붙여야 동작한다. 암호 코어(`crypto_core`)는
이미 테스트 완료이므로, 전송 계층은 "바이트 왕복"만 실기기에서 확인하면 된다.
