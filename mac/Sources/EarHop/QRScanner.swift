import AVFoundation
import AppKit
import CoreImage
import SwiftUI

/// Reads QR codes from the Mac's camera. Frames are checked a few times a second with Core Image; nothing is saved or
/// sent anywhere, and the camera turns off as soon as a code is found or the sheet closes.
final class QRScanner: NSObject, ObservableObject, AVCaptureVideoDataOutputSampleBufferDelegate {
    @Published var problem: String?
    @Published var found: String?
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "earhop.qr")
    private let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
    private var lastCheck = Date.distantPast

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async { if ok { self.configure() } else { self.problem = "Camera access was not allowed." } }
            }
        default:
            problem = "EarHop may not use the camera. Allow it in System Settings, Privacy & Security, Camera."
        }
    }

    private func configure() {
        guard let cam = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: cam) else {
            problem = "No camera found."
            return
        }
        session.beginConfiguration()
        session.sessionPreset = .high
        if session.canAddInput(input) { session.addInput(input) }
        let out = AVCaptureVideoDataOutput()
        out.alwaysDiscardsLateVideoFrames = true
        out.setSampleBufferDelegate(self, queue: queue)
        if session.canAddOutput(out) { session.addOutput(out) }
        session.commitConfiguration()
        queue.async { self.session.startRunning() }
        Log.write("pairing: camera scan started")
    }

    func stop() {
        queue.async { if self.session.isRunning { self.session.stopRunning() } }
    }

    func captureOutput(_ output: AVCaptureOutput, didOutput buffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard found == nil, Date().timeIntervalSince(lastCheck) > 0.25, let px = CMSampleBufferGetImageBuffer(buffer) else { return }
        lastCheck = Date()
        let image = CIImage(cvPixelBuffer: px)
        guard let code = detector?.features(in: image).compactMap({ ($0 as? CIQRCodeFeature)?.messageString }).first else { return }
        DispatchQueue.main.async {
            guard self.found == nil else { return }
            self.found = code
            self.stop()
        }
    }
}

/// The camera picture, mirrored like a FaceTime preview so moving the code feels natural.
private struct CameraPreview: NSViewRepresentable {
    let session: AVCaptureSession
    func makeNSView(context: Context) -> NSView {
        let v = NSView()
        v.wantsLayer = true
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        layer.connection?.automaticallyAdjustsVideoMirroring = false
        layer.connection?.isVideoMirrored = true
        v.layer = layer
        return v
    }
    func updateNSView(_ nsView: NSView, context: Context) {}
}

/// "Scan with camera": hold another device's pairing QR code up to the Mac's camera. Calls back with the text read,
/// or nil on Cancel.
struct QRScanSheet: View {
    let done: (String?) -> Void
    @StateObject private var scanner = QRScanner()

    var body: some View {
        VStack(spacing: 14) {
            Text("Scan a Pairing Code").font(.title3.weight(.semibold))
            Text("Hold the QR code from EarHop on your other device in front of this Mac's camera.")
                .multilineTextAlignment(.center).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            ZStack {
                RoundedRectangle(cornerRadius: 14).fill(Color.black)
                if scanner.problem == nil { CameraPreview(session: scanner.session).clipShape(RoundedRectangle(cornerRadius: 14)) }
                RoundedRectangle(cornerRadius: 18).strokeBorder(Color.white.opacity(0.85), style: StrokeStyle(lineWidth: 3, dash: [22, 12]))
                    .frame(width: 190, height: 190)
                if let p = scanner.problem {
                    Text(p).foregroundStyle(.white).multilineTextAlignment(.center).padding()
                }
            }
            .frame(width: 400, height: 300)
            HStack {
                if scanner.problem != nil {
                    Button("Open Camera Settings") {
                        NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Camera")!)
                    }
                }
                Spacer()
                Button("Cancel") { scanner.stop(); done(nil) }.keyboardShortcut(.cancelAction)
            }
        }
        .padding(20)
        .frame(width: 440)
        .onAppear { scanner.start() }
        .onDisappear { scanner.stop() }
        .onReceive(scanner.$found) { code in if let code { done(code) } }
    }
}
