import Foundation
import PokerCore
import SwiftUI
import UIKit

/// App language (Persian by default), switchable inside the app.
enum Lang {
    static var code: String = UserDefaults.standard.string(forKey: "lang") ?? "fa"
    static var isRTL: Bool { code == "fa" }

    private static var bundle: Bundle {
        Bundle.main.path(forResource: code, ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main
    }

    static func tr(_ key: String) -> String { bundle.localizedString(forKey: key, value: key, table: nil) }

    static func tr(_ key: String, _ args: CVarArg...) -> String {
        String(format: tr(key), locale: Locale(identifier: code), arguments: args)
    }
}

func tr(_ key: String) -> String { Lang.tr(key) }
func tr(_ key: String, _ args: CVarArg...) -> String {
    String(format: Lang.tr(key), locale: Locale(identifier: Lang.code), arguments: args)
}

enum ScanPhase: Equatable {
    case idle
    case working
    case result
    case failed
}

/// Screen state for the live table. All Kotlin session calls happen on the main thread; reading
/// a photo and computing advice run on background queues.
final class LiveModel: ObservableObject {
    let store: MemoryStore
    let session: LiveSession
    let camera = CameraController()
    private let detector = CardDetector()
    private let work = DispatchQueue(label: "scan", qos: .userInitiated)
    private let math = DispatchQueue(label: "advice", qos: .userInitiated)
    private var adviceGeneration = 0

    @Published private(set) var revision = 0
    @Published private(set) var phase: ScanPhase = .idle
    @Published private(set) var photo: UIImage?
    @Published private(set) var scanMillis: Int64 = 0
    @Published private(set) var advice: AdviceView?
    @Published private(set) var computing = false
    @Published private(set) var detectorStatus: CardDetector.Status = .loading
    @Published private(set) var learned = 0
    @Published var manualOnly = UserDefaults.standard.bool(forKey: "manualOnly") {
        didSet {
            UserDefaults.standard.set(manualOnly, forKey: "manualOnly")
            if manualOnly { closeResult() }
        }
    }
    @Published var language = Lang.code {
        didSet {
            Lang.code = language
            UserDefaults.standard.set(language, forKey: "lang")
        }
    }

    private var photoImage: CGImage?

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        store = MemoryStore(path: dir.appendingPathComponent("card_memory.bin").path)
        session = LiveSession(memory: store, variantId: UserDefaults.standard.string(forKey: "variant") ?? "TEXAS_HOLDEM")
        work.async {
            let status = self.detector.prepare()
            DispatchQueue.main.async { self.detectorStatus = status }
        }
        changed()
    }

    /// Call after every change to the session: refreshes the screen and the advice.
    private func changed() {
        revision += 1
        learned = Int(store.exampleCount)
        adviceGeneration += 1
        let generation = adviceGeneration
        let request = session.adviceRequest()
        computing = true
        math.async {
            let a = Advisor.shared.analyze(request: request)
            DispatchQueue.main.async {
                guard generation == self.adviceGeneration else { return }
                self.advice = a
                self.computing = false
            }
        }
    }

    // MARK: camera and photo

    var cameraWanted: Bool { !manualOnly && phase != .result }

    func scan(_ target: String) {
        guard phase != .working, detectorStatus == .ready else { return }
        phase = .working
        camera.capture { image in
            guard let image, let cg = uprightImage(image) else {
                DispatchQueue.main.async { self.phase = .failed }
                return
            }
            self.work.async {
                let reading = self.detector.read(cg, store: self.store)
                DispatchQueue.main.async {
                    self.session.applyReading(target: target, scan: reading, photoWidth: Int32(cg.width), photoHeight: Int32(cg.height))
                    self.photoImage = cg
                    self.photo = UIImage(cgImage: cg)
                    self.scanMillis = reading.millis
                    self.phase = .result
                    self.changed()
                }
            }
        }
    }

    func closeResult() {
        guard phase == .result || phase == .failed else { return }
        session.closeResult()
        phase = .idle
        photo = nil
        photoImage = nil
        changed()
    }

    func acceptPending(_ code: String) {
        session.acceptPending(code: code)
        changed()
    }

    func correctBox(_ index: Int, _ code: String) {
        session.correctBox(index: Int32(index), code: code)
        changed()
    }

    func dismissBox(_ index: Int) {
        session.dismissBox(index: Int32(index))
        changed()
    }

    /// The user tapped a missed card at (x, y) on the photo (0..1) and chose [code].
    func teach(x: Float, y: Float, code: String) {
        let box = session.tapBox(x: x, y: y)
        let pixels = photoImage.flatMap { glyphPixels($0, box) }
        session.teachAt(box: box, code: code, rgbx: pixels)
        changed()
    }

    // MARK: table

    func setVariant(_ id: String) {
        UserDefaults.standard.set(id, forKey: "variant")
        session.setVariant(id: id)
        phase = .idle
        photo = nil
        changed()
    }

    func addCards(_ codes: [String], target: String) {
        session.addCards(codes: codes, target: target)
        changed()
    }

    func remove(_ code: String) {
        session.remove(code: code)
        changed()
    }

    func setOpponents(_ n: Int) {
        session.setOpponents(n: Int32(n))
        changed()
    }

    func setDrawDone(_ done: Bool) {
        session.setDrawDone(done: done)
        changed()
    }

    func newHand() {
        session.newHand()
        phase = .idle
        photo = nil
        photoImage = nil
        changed()
    }

    func forgetLearned() {
        store.forgetAll()
        changed()
    }
}
