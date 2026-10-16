import SwiftUI

@main
struct PRX917App: App {
    @StateObject private var model = RadioModel()
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(model)
        }
    }
}
