import SwiftUI

@main
struct PokerVisionApp: App {
    @StateObject private var model = LiveModel()

    var body: some Scene {
        WindowGroup {
            LiveTableView(model: model, camera: model.camera)
        }
    }
}
