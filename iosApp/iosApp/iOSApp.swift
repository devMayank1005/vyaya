import SwiftUI
import Shared

/// The iOS app: a SwiftUI window showing the shared Compose screens (shared/src/iosMain, IosApp.kt).
@main
struct iOSApp: App {
    init() {
        // Encrypted backups use CryptoKit's AES-GCM, which only Swift can call.
        IosCrypto.shared.aesGcm = CryptoKitAesGcm()
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { IosAppKt.MainViewController() }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
