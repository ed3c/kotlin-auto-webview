import SwiftUI
import KotlinAutoWebView

@main
struct KotlinAutoWebViewApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeRootView().ignoresSafeArea(.all)
        }
    }
}

struct ComposeRootView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
