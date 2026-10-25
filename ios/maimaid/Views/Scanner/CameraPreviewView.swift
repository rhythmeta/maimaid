import SwiftUI

struct CameraPreviewView: UIViewControllerRepresentable {
    var onImageCaptured: (UIImage) -> Void
    var onPhotoCaptured: (Result<Data, Error>) -> Void
    var onQRCodeDetected: (String) -> Void
    var onSGWCMAIDCodeDetected: (String) -> Void

    func makeUIViewController(context: Context) -> CameraViewController {
        let controller = CameraViewController()
        controller.onImageCaptured = onImageCaptured
        controller.onPhotoCaptured = onPhotoCaptured
        controller.onQRCodeDetected = onQRCodeDetected
        controller.onSGWCMAIDCodeDetected = onSGWCMAIDCodeDetected
        return controller
    }

    func updateUIViewController(_ uiViewController: CameraViewController, context: Context) {
        uiViewController.onImageCaptured = onImageCaptured
        uiViewController.onPhotoCaptured = onPhotoCaptured
        uiViewController.onQRCodeDetected = onQRCodeDetected
        uiViewController.onSGWCMAIDCodeDetected = onSGWCMAIDCodeDetected
    }
}
