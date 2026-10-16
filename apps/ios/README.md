# PRX-917 — iOS 앱

SwiftUI 앱. `crypto_core`(Rust)를 XCFramework로 링크하고, MultipeerConnectivity로
오프그리드 1:1 교신한다.

> ⚠️ **빌드에는 macOS + Xcode가 필요하다.** Windows에선 소스 작성까지만 가능하다.
> 아이폰에 설치하려면 Apple 서명(무료/유료 개발자 계정)이 필요하다.

## 구성
```
apps/ios/
├─ project.yml                 XcodeGen 프로젝트 정의
├─ scripts/build-xcframework.sh  crypto_core → CryptoCore.xcframework (macOS 전용)
└─ Sources/
   ├─ PRX917App.swift          앱 진입점
   ├─ RadioModel.swift         crypto_core + 전송 글루 (ObservableObject)
   └─ RootView.swift           화면(잠금/채널/탐색/안전번호/채팅)
```
공유 소스도 함께 컴파일된다:
- `crypto_core/bindings/swift/crypto_core.swift` (UniFFI 바인딩)
- `transport/ios/MultipeerTransport.swift` (전송)

## 경로 1 — Mac이 있을 때
```bash
# 1) Rust Apple 타깃
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
# 2) XCFramework 빌드
bash apps/ios/scripts/build-xcframework.sh
# 3) Xcode 프로젝트 생성 (brew install xcodegen 필요)
cd apps/ios && xcodegen generate
# 4) 열어서 실행
open PRX917.xcodeproj
```
실기기 설치: Xcode에서 본인 Apple ID(무료)로 서명 → 아이폰 연결 → Run.

## 경로 2 — Mac이 없을 때 (클라우드 빌드)
`.github/workflows/ios.yml` 가 GitHub Actions의 **macOS 러너**에서
XCFramework + 앱을 빌드한다(시뮬레이터용, 무서명 — 컴파일 검증용).
- 저장소 Actions 탭 → **iOS Build** 수동 실행
- 실기기 배포까지 하려면 Apple Developer Program($99/년) + 서명 시크릿을 CI에 추가해
  TestFlight 업로드 단계를 붙여야 한다(추후).

## 현재 상태
- 소스·빌드 자동화 스캐폴드 완료. **아직 Mac/CI에서 컴파일하지 않았으므로** 첫 빌드에서
  소소한 수정이 있을 수 있다(서명 설정, Info 권한 등).
- `crypto_core`는 Windows에서 테스트 23건 통과(검증됨).
