import SwiftUI
import UIKit

/// Wraps `UIActivityViewController` so the chart package can go through the system share sheet.
/// SwiftUI has no equivalent that accepts an already-produced file plus a custom completion.
struct ActivityShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]
    var onComplete: (() -> Void)?

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
        controller.completionWithItemsHandler = { _, _, _, _ in
            onComplete?()
        }
        return controller
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
