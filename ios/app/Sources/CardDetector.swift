import CoreGraphics
import Foundation
import OnnxRuntimeBindings
import PokerCore

/// Runs the card model (the same ONNX file as the Android app) and hands its output to the shared
/// Kotlin code, which reads the cards and applies what the user taught.
final class CardDetector {
    enum Status: Equatable { case loading, ready, missing, failed }

    private var env: ORTEnv?
    private var session: ORTSession?
    private var inputName = "images"
    private var outputName = "output0"
    private var labels: [String] = []

    /// Loads the model. Call on a background queue.
    func prepare() -> Status {
        guard let path = Bundle.main.path(forResource: "card_detector", ofType: "onnx") else { return .missing }
        if let url = Bundle.main.url(forResource: "card_detector.classes", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let codes = try? JSONSerialization.jsonObject(with: data) as? [String] {
            labels = codes
        }
        do {
            let env = try ORTEnv(loggingLevel: .warning)
            let options = try ORTSessionOptions()
            try options.setIntraOpNumThreads(4)
            try options.setGraphOptimizationLevel(.all)
            let s = try ORTSession(env: env, modelPath: path, sessionOptions: options)
            if let name = try s.inputNames().first { inputName = name }
            if let name = try s.outputNames().first { outputName = name }
            self.env = env
            session = s
            return .ready
        } catch {
            NSLog("Card model failed: \(error)")
            return .failed
        }
    }

    /// Reads one upright photo (long side about 2048 px). Call on a background queue.
    func read(_ image: CGImage, store: MemoryStore) -> ScanReading {
        let start = Date()
        let scan = PhotoScan(photoWidth: Int32(image.width), photoHeight: Int32(image.height), labelCodes: labels)
        if session != nil {
            for (i, r) in scan.regions().enumerated() {
                let rect = CGRect(x: Int(r.left), y: Int(r.top), width: Int(r.width), height: Int(r.height))
                guard let crop = image.cropping(to: rect) else { continue }
                let lb = scan.letterbox(regionIndex: Int32(i))
                if let out = run(input(crop, lb)) {
                    scan.addOutput(regionIndex: Int32(i), output: out)
                }
            }
            for (i, b) in scan.proposals().enumerated() {
                if let px = glyphPixels(image, b) {
                    scan.setGlyph(index: Int32(i), rgbx: px)
                }
            }
        }
        return scan.finish(store: store, millis: Int64(Date().timeIntervalSince(start) * 1000))
    }

    /// Letterbox into 640x640 (gray 114 padding), RGB 0..1, NCHW — as the model was trained.
    private func input(_ crop: CGImage, _ lb: LetterboxView) -> NSMutableData {
        let size = Int(lb.size)
        let rgbx = drawRGBX(width: size, height: size) { ctx in
            ctx.setFillColor(CGColor(red: 114 / 255, green: 114 / 255, blue: 114 / 255, alpha: 1))
            ctx.fill(CGRect(x: 0, y: 0, width: size, height: size))
            // Core Graphics counts y from the bottom.
            let y = size - Int(lb.padY) - Int(lb.scaledHeight)
            ctx.draw(crop, in: CGRect(x: Int(lb.padX), y: y, width: Int(lb.scaledWidth), height: Int(lb.scaledHeight)))
        }
        let plane = size * size
        var chw = [Float](repeating: 0, count: 3 * plane)
        rgbx.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            let p = raw.bindMemory(to: UInt8.self)
            for i in 0..<plane {
                chw[i] = Float(p[4 * i]) / 255
                chw[plane + i] = Float(p[4 * i + 1]) / 255
                chw[2 * plane + i] = Float(p[4 * i + 2]) / 255
            }
        }
        return chw.withUnsafeBytes { NSMutableData(bytes: $0.baseAddress!, length: $0.count) }
    }

    private func run(_ tensor: NSMutableData) -> Data? {
        guard let s = session else { return nil }
        do {
            let value = try ORTValue(tensorData: tensor, elementType: .float, shape: [1, 3, 640, 640])
            let outputs = try s.run(withInputs: [inputName: value], outputNames: [outputName], runOptions: nil)
            guard let out = outputs[outputName] else { return nil }
            return try out.tensorData() as Data
        } catch {
            NSLog("Card model run failed: \(error)")
            return nil
        }
    }
}

/// Pixels of a corner symbol (box normalized to the image, plus a small margin), resized to the
/// patch size the shared code expects. Same cut as the Android app.
func glyphPixels(_ image: CGImage, _ box: BoxView) -> Data? {
    let w = CGFloat(image.width)
    let h = CGFloat(image.height)
    let pad: CGFloat = 0.1
    let bw = CGFloat(box.width)
    let bh = CGFloat(box.height)
    let left = max(0, min(w - 1, ((CGFloat(box.left) - bw * pad) * w).rounded()))
    let top = max(0, min(h - 1, ((CGFloat(box.top) - bh * pad) * h).rounded()))
    let right = max(left + 1, min(w, ((CGFloat(box.right) + bw * pad) * w).rounded()))
    let bottom = max(top + 1, min(h, ((CGFloat(box.bottom) + bh * pad) * h).rounded()))
    guard right - left >= 6, bottom - top >= 6,
          let crop = image.cropping(to: CGRect(x: left, y: top, width: right - left, height: bottom - top))
    else { return nil }
    let gw = Int(GlyphSize.shared.width)
    let gh = Int(GlyphSize.shared.height)
    return drawRGBX(width: gw, height: gh) { ctx in
        ctx.interpolationQuality = .high
        ctx.draw(crop, in: CGRect(x: 0, y: 0, width: gw, height: gh))
    }
}

/// Draws into an RGBX buffer (4 bytes per pixel, red first, top row first).
func drawRGBX(width: Int, height: Int, _ draw: (CGContext) -> Void) -> Data {
    var data = Data(count: width * height * 4)
    data.withUnsafeMutableBytes { (raw: UnsafeMutableRawBufferPointer) in
        guard let ctx = CGContext(
            data: raw.baseAddress,
            width: width,
            height: height,
            bitsPerComponent: 8,
            bytesPerRow: width * 4,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ) else { return }
        draw(ctx)
    }
    return data
}
