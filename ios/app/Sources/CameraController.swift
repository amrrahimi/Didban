import AVFoundation
import SwiftUI
import UIKit

/// Back camera for still photos. Runs only while a photo is about to be taken; the app stops it
/// once the photo is in, while cards are chosen by hand, and in manual mode.
final class CameraController: NSObject, ObservableObject, AVCapturePhotoCaptureDelegate {
    let session = AVCaptureSession()
    private let output = AVCapturePhotoOutput()
    private let queue = DispatchQueue(label: "camera")
    private var configured = false
    private var device: AVCaptureDevice?
    private var completion: ((UIImage?) -> Void)?

    @Published private(set) var ready = false
    @Published private(set) var failed = false
    @Published private(set) var denied = false

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async {
                    if ok { self.startSession() } else { self.denied = true }
                }
            }
        default:
            denied = true
        }
    }

    private func startSession() {
        queue.async {
            if !self.configured { self.configure() }
            guard self.configured else {
                DispatchQueue.main.async { self.failed = true }
                return
            }
            if !self.session.isRunning { self.session.startRunning() }
            DispatchQueue.main.async { self.ready = self.session.isRunning }
        }
    }

    func stop() {
        ready = false
        queue.async {
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    private func configure() {
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .photo // 4:3, what the preview shows is what is captured
        guard
            let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
            let input = try? AVCaptureDeviceInput(device: camera),
            session.canAddInput(input),
            session.canAddOutput(output)
        else { return }
        session.addInput(input)
        session.addOutput(output)
        output.maxPhotoQualityPrioritization = .quality
        device = camera
        configured = true
    }

    /// Takes one photo. [done] runs on a background queue.
    func capture(_ done: @escaping (UIImage?) -> Void) {
        queue.async {
            guard self.session.isRunning else {
                done(nil)
                return
            }
            self.completion = done
            let settings = AVCapturePhotoSettings()
            settings.photoQualityPrioritization = .balanced
            self.output.capturePhoto(with: settings, delegate: self)
        }
    }

    func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
        let image = error == nil ? photo.fileDataRepresentation().flatMap { UIImage(data: $0) } : nil
        let done = completion
        completion = nil
        done?(image)
    }

    /// Focus and exposure at a point in camera coordinates (0..1).
    func focus(at point: CGPoint) {
        queue.async {
            guard let d = self.device, (try? d.lockForConfiguration()) != nil else { return }
            if d.isFocusPointOfInterestSupported, d.isFocusModeSupported(.autoFocus) {
                d.focusPointOfInterest = point
                d.focusMode = .autoFocus
            }
            if d.isExposurePointOfInterestSupported, d.isExposureModeSupported(.autoExpose) {
                d.exposurePointOfInterest = point
                d.exposureMode = .autoExpose
            }
            d.unlockForConfiguration()
        }
    }
}

/// Live camera picture; tap to focus.
struct CameraPreview: UIViewRepresentable {
    let camera: CameraController

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        var onTap: ((CGPoint) -> Void)?

        @objc func tapped(_ g: UITapGestureRecognizer) {
            let p = previewLayer.captureDevicePointConverted(fromLayerPoint: g.location(in: self))
            onTap?(p)
        }
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        v.backgroundColor = .black
        v.previewLayer.session = camera.session
        v.previewLayer.videoGravity = .resizeAspect
        v.onTap = { [weak camera] p in camera?.focus(at: p) }
        v.addGestureRecognizer(UITapGestureRecognizer(target: v, action: #selector(PreviewView.tapped(_:))))
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}
}

/// Upright copy of a photo with its long side at most [longSide] pixels.
func uprightImage(_ image: UIImage, longSide: CGFloat = 2048) -> CGImage? {
    let s = image.size
    guard s.width > 0, s.height > 0 else { return nil }
    let scale = min(1, longSide / max(s.width * image.scale, s.height * image.scale)) * image.scale
    let size = CGSize(width: (s.width * scale).rounded(), height: (s.height * scale).rounded())
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    return UIGraphicsImageRenderer(size: size, format: format).image { _ in
        image.draw(in: CGRect(origin: .zero, size: size))
    }.cgImage
}
