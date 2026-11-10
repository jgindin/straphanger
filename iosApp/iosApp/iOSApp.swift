import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    if url.scheme == "straphanger" || url.scheme == "nycsubway" {
                        SharedRouteInbox.shared.enqueueSharedRouteText(text: url.absoluteString)
                    }
                }
        }
    }
}
