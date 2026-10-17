import SwiftUI

@main
struct PRX917App: App {
    @StateObject private var model = RadioModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                // 앱 전환기 스냅샷·비활성 상태에서 대화 내용 가림
                .overlay {
                    if scenePhase != .active && model.isUnlocked {
                        ZStack {
                            Color.scr.ignoresSafeArea()
                            Text("PRX-917").font(.system(.title2, design: .monospaced).bold())
                                .foregroundColor(.scrDim)
                        }
                    }
                }
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .background: model.appDidEnterBackground()
            case .active:     model.appWillEnterForeground()
            default: break
            }
        }
    }
}
