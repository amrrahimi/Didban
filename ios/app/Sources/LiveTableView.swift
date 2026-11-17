import PokerCore
import SwiftUI

private let ivory = Color(red: 0.98, green: 0.96, blue: 0.90)
private let gold = Color(red: 0.83, green: 0.69, blue: 0.22)
private let good = Color(red: 0.29, green: 0.87, blue: 0.50)
private let medium = Color(red: 0.98, green: 0.80, blue: 0.08)
private let bad = Color(red: 0.97, green: 0.44, blue: 0.44)
private let cardRed = Color(red: 0.80, green: 0.10, blue: 0.10)

/// What the card picker is for.
private enum Picking: Identifiable {
    case add(target: String)
    case correct(index: Int)
    case teach(x: Float, y: Float)

    var id: String {
        switch self {
        case .add(let t): return "add-\(t)"
        case .correct(let i): return "correct-\(i)"
        case .teach(let x, let y): return "teach-\(x)-\(y)"
        }
    }
}

struct LiveTableView: View {
    @ObservedObject var model: LiveModel
    @ObservedObject var camera: CameraController
    @State private var picking: Picking?
    @State private var removing: CardView?
    @State private var confirmForget = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if !model.manualOnly {
                    photoArea
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background(Color.black)
                }
                controls
                    .frame(maxHeight: model.manualOnly ? .infinity : nil)
            }
            .background(Color.black.ignoresSafeArea())
            .navigationTitle(tr("live_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Color.black, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button(model.language == "fa" ? "EN" : "فا") {
                        model.language = model.language == "fa" ? "en" : "fa"
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) { variantMenu }
            }
        }
        .environment(\.layoutDirection, model.language == "fa" ? .rightToLeft : .leftToRight)
        .preferredColorScheme(.dark)
        .onAppear(perform: updateCamera)
        .onChange(of: model.cameraWanted) { _ in updateCamera() }
        .onChange(of: picking == nil) { _ in updateCamera() }
        .sheet(item: $picking) { p in pickerSheet(p) }
        .alert(tr("learn_memory_forget"), isPresented: $confirmForget) {
            Button(tr("live_reject_yes"), role: .destructive) { model.forgetLearned() }
            Button(tr("live_reject_no"), role: .cancel) {}
        } message: {
            Text(tr("learn_memory_forget_confirm"))
        }
        .confirmationDialog(
            removing.map { tr("live_reject_question", label($0)) } ?? "",
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
            titleVisibility: .visible
        ) {
            Button(tr("live_reject_yes"), role: .destructive) {
                if let c = removing { model.remove(c.code) }
                removing = nil
            }
            Button(tr("live_reject_no"), role: .cancel) { removing = nil }
        }
    }

    private func updateCamera() {
        if model.cameraWanted && picking == nil { camera.start() } else { camera.stop() }
    }

    // MARK: top area

    @ViewBuilder private var photoArea: some View {
        ZStack {
            if model.phase == .result, let photo = model.photo {
                PhotoWithBoxes(
                    photo: photo,
                    boxes: model.session.boxes,
                    revision: model.revision,
                    onBoxTap: { picking = .correct(index: $0) },
                    onEmptyTap: { x, y in picking = .teach(x: x, y: y) }
                )
            } else {
                CameraPreview(camera: camera)
                    .aspectRatio(3.0 / 4.0, contentMode: .fit)
                if model.phase == .working {
                    Color.black.opacity(0.5)
                    VStack(spacing: 8) {
                        ProgressView().tint(.white)
                        Text(tr("scan_reading")).foregroundColor(.white)
                    }
                }
            }
            VStack {
                if let text = statusText {
                    Text(text)
                        .font(.footnote)
                        .foregroundColor(.white)
                        .padding(8)
                        .background(Color.red.opacity(0.8))
                        .cornerRadius(8)
                        .padding(8)
                }
                Spacer()
            }
        }
        .environment(\.layoutDirection, .leftToRight)
    }

    private var statusText: String? {
        if camera.denied { return tr("ios_camera_denied") }
        if camera.failed { return tr("live_camera_error") }
        switch model.detectorStatus {
        case .missing: return tr("live_model_missing")
        case .failed: return tr("live_model_failed")
        case .loading, .ready: break
        }
        if model.phase == .failed { return tr("scan_failed") }
        return nil
    }

    // MARK: bottom panel

    private var controls: some View {
        let s = model.session
        let v = s.variant
        return ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                if model.phase == .result { resultBar }
                Toggle(isOn: $model.manualOnly) {
                    Text(tr(model.manualOnly ? "scan_manual_on" : "scan_manual_off")).font(.footnote)
                }
                .tint(gold)
                CardRow(title: tr("live_my_cards"), cards: s.mine, slots: Int(s.mineCapacity),
                        onCardTap: { removing = $0 }, onEmptyTap: { picking = .add(target: "mine") })
                if v.usesBoard {
                    CardRow(title: tr("live_board"), cards: s.board, slots: Int(s.boardCapacity),
                            onCardTap: { removing = $0 }, onEmptyTap: { picking = .add(target: "table") })
                } else if v.isStud {
                    CardRow(title: tr("scan_seen"), cards: s.seen, slots: s.seen.count + 1,
                            onCardTap: { removing = $0 }, onEmptyTap: { picking = .add(target: "table") })
                }
                AdvicePanel(advice: model.advice, computing: model.computing)
                if v.isDraw {
                    Toggle(isOn: Binding(get: { s.drawDone }, set: { model.setDrawDone($0) })) {
                        Text(tr("scan_draw_done"))
                    }
                    .tint(gold)
                }
                scanButtons
                HStack {
                    Text(tr("live_opponents"))
                    Spacer()
                    Button { model.setOpponents(Int(s.opponents) - 1) } label: { Image(systemName: "minus.circle") }
                        .disabled(s.opponents <= 1)
                    Text("\(Int(s.opponents))").font(.title2.bold()).frame(minWidth: 32)
                    Button { model.setOpponents(Int(s.opponents) + 1) } label: { Image(systemName: "plus.circle") }
                        .disabled(s.opponents >= s.maxOpponents)
                }
                .font(.title3)
                Button { model.newHand() } label: {
                    Label(tr("live_new_hand"), systemImage: "arrow.clockwise").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(gold)
                HStack {
                    Text(model.learned == 0 ? tr("learn_memory_empty") : tr("learn_memory_count", model.learned))
                        .font(.caption)
                        .foregroundColor(.white.opacity(0.7))
                    Spacer()
                    if model.learned > 0 {
                        Button(tr("learn_memory_forget")) { confirmForget = true }.font(.caption)
                    }
                }
            }
            .padding(12)
            .foregroundColor(.white)
            .id(model.revision)
        }
        .background(Color.black)
    }

    @ViewBuilder private var scanButtons: some View {
        let v = model.session.variant
        if model.phase == .result {
            Button { model.closeResult() } label: {
                Label(tr("scan_new_photo"), systemImage: "camera").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(gold)
        } else if !model.manualOnly {
            let busy = model.phase == .working || model.detectorStatus != .ready || !camera.ready
            HStack {
                Button { model.scan("mine") } label: {
                    Label(tr("scan_mine"), systemImage: "camera").frame(maxWidth: .infinity)
                }
                if v.tableScan {
                    Button { model.scan("table") } label: {
                        Label(tr(v.usesBoard ? "scan_board" : "scan_upcards"), systemImage: "camera").frame(maxWidth: .infinity)
                    }
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(gold)
            .disabled(busy)
        }
    }

    private var resultBar: some View {
        let s = model.session
        return VStack(alignment: .leading, spacing: 6) {
            Text(tr("scan_result", s.boxes.filter { $0.accepted }.count, Int(model.scanMillis))).font(.subheadline)
            if !s.pending.isEmpty {
                Text(tr("scan_pending_help")).font(.caption).foregroundColor(medium)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack {
                        ForEach(s.pending, id: \.code) { c in
                            Button(label(c) + " ?") { model.acceptPending(c.code) }
                                .buttonStyle(.bordered)
                                .tint(medium)
                        }
                    }
                }
            }
            Text(tr("learn_tap_help")).font(.caption).foregroundColor(.white.opacity(0.8))
        }
    }

    private var variantMenu: some View {
        Menu {
            ForEach(LiveSession.Companion.shared.variants(), id: \.id) { v in
                Button(tr("variant_" + v.id)) { model.setVariant(v.id) }
            }
        } label: {
            Text(tr("variant_" + model.session.variant.id) + " ▾").foregroundColor(gold)
        }
    }

    // MARK: card picker

    @ViewBuilder private func pickerSheet(_ p: Picking) -> some View {
        let s = model.session
        switch p {
        case .add(let target):
            CardPicker(
                rows: s.deckRows(),
                used: Set(s.usedCodes()),
                title: tr(target == "mine" ? "live_my_cards" : "live_board"),
                maxSelect: max(1, Int(s.freeSlots(target: target))),
                onNotACard: nil,
                onDone: { codes in
                    model.addCards(codes, target: target)
                    picking = nil
                },
                onCancel: { picking = nil }
            )
        case .correct(let index):
            let current = index < s.boxes.count ? label(s.boxes[index].view) : ""
            CardPicker(
                rows: s.deckRows(),
                used: [],
                title: tr("learn_correct_title", current),
                maxSelect: 1,
                onNotACard: {
                    model.dismissBox(index)
                    picking = nil
                },
                onDone: { codes in
                    if let c = codes.first { model.correctBox(index, c) }
                    picking = nil
                },
                onCancel: { picking = nil }
            )
        case .teach(let x, let y):
            CardPicker(
                rows: s.deckRows(),
                used: [],
                title: tr("learn_teach_title"),
                maxSelect: 1,
                onNotACard: nil,
                onDone: { codes in
                    if let c = codes.first { model.teach(x: x, y: y, code: c) }
                    picking = nil
                },
                onCancel: { picking = nil }
            )
        }
    }
}

func label(_ c: CardView) -> String { c.rank + c.suit }

// MARK: - pieces

struct CardChip: View {
    let card: CardView

    var body: some View {
        VStack(spacing: 0) {
            Text(card.rank).font(.system(size: 15, weight: .bold))
            Text(card.suit).font(.system(size: 13))
        }
        .foregroundColor(card.isRed ? cardRed : .black)
        .frame(width: 36, height: 50)
        .background(ivory)
        .cornerRadius(5)
        .environment(\.layoutDirection, .leftToRight)
    }
}

struct CardRow: View {
    let title: String
    let cards: [CardView]
    let slots: Int
    let onCardTap: (CardView) -> Void
    let onEmptyTap: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            Text(title).font(.subheadline.bold()).frame(width: 64, alignment: .leading)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(0..<max(slots, cards.count), id: \.self) { i in
                        if i < cards.count {
                            CardChip(card: cards[i]).onTapGesture { onCardTap(cards[i]) }
                        } else {
                            Button(action: onEmptyTap) {
                                Text("+")
                                    .frame(width: 36, height: 50)
                                    .overlay(RoundedRectangle(cornerRadius: 5).stroke(Color.white.opacity(0.5)))
                            }
                            .accessibilityLabel(tr("live_add_card"))
                        }
                    }
                }
            }
        }
    }
}

struct AdvicePanel: View {
    let advice: AdviceView?
    let computing: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let a = advice {
                if let issue = a.issue {
                    Text(waiting(a, issue)).font(.headline)
                } else {
                    ready(a)
                }
            } else {
                Text(tr("scan_computing"))
            }
        }
        .opacity(computing && advice != nil ? 0.6 : 1)
    }

    private func waiting(_ a: AdviceView, _ issue: String) -> String {
        switch issue {
        case "need_mine":
            return a.needMin == a.needMax ? tr("scan_need_mine_exact", Int(a.needMin)) : tr("scan_need_mine_range", Int(a.needMin), Int(a.needMax))
        case "need_board": return tr("live_waiting_board")
        case "deck": return tr("scan_issue_deck")
        case "duplicate": return tr("scan_issue_duplicate")
        default: return tr("scan_issue_opponents")
        }
    }

    @ViewBuilder private func ready(_ a: AdviceView) -> some View {
        if let keep = a.drawKeep {
            Text(tr("scan_draw_advice", keep.isEmpty ? "—" : keep.map(label).joined(separator: " "), Int(a.drawDiscardCount)))
                .font(.system(size: 18, weight: .bold))
        }
        // Action and chance to win side by side, both large and in the action's colour.
        HStack(alignment: .center) {
            Text(actionTitle(a)).font(.system(size: 30, weight: .bold)).foregroundColor(actionColor(a))
            Spacer()
            VStack(alignment: .trailing, spacing: 0) {
                Text(tr("ios_percent", Int(a.equityPercent)))
                    .font(.system(size: 34, weight: .bold))
                    .foregroundColor(actionColor(a))
                Text(tr("live_chance_label") + " " + (a.plusMinusPercent < 0 ? tr("live_chance_exact") : tr("live_chance_estimate", tr("ios_percent", Int(a.plusMinusPercent)))))
                    .font(.caption2)
                    .foregroundColor(actionColor(a).opacity(0.85))
            }
        }
        Text(detail(a)).font(.subheadline)
        if !a.handKey.isEmpty {
            Text(tr("live_current_hand", tr("hand_" + a.handKey)))
                .font(.caption)
                .foregroundColor(.white.opacity(0.85))
        }
        if a.hiLo {
            Text(tr("scan_hilo_note")).font(.caption).foregroundColor(.white.opacity(0.7))
        }
    }

    private func actionTitle(_ a: AdviceView) -> String {
        switch a.action {
        case "bet": return tr("live_action_bet")
        case "call": return tr("live_action_call")
        default: return tr("live_action_fold")
        }
    }

    private func actionColor(_ a: AdviceView) -> Color {
        switch a.action {
        case "bet": return good
        case "call": return medium
        default: return bad
        }
    }

    private func detail(_ a: AdviceView) -> String {
        switch a.action {
        case "bet": return tr("live_detail_bet")
        case "call": return a.maxCallPercent < 0 ? tr("live_detail_call_any") : tr("live_detail_call_up_to", Int(a.maxCallPercent))
        default: return tr("live_detail_fold")
        }
    }
}

/// The scanned photo with a labelled box per card. Tap a box to correct it, tap elsewhere to add a
/// missed card.
struct PhotoWithBoxes: View {
    let photo: UIImage
    let boxes: [PhotoBox]
    let revision: Int
    let onBoxTap: (Int) -> Void
    let onEmptyTap: (Float, Float) -> Void

    var body: some View {
        Image(uiImage: photo)
            .resizable()
            .scaledToFit()
            .overlay(
                GeometryReader { geo in
                    let w = geo.size.width
                    let h = geo.size.height
                    ZStack(alignment: .topLeading) {
                        Color.clear
                        ForEach(Array(boxes.enumerated()), id: \.offset) { _, b in
                            let color = b.byUser ? gold : (b.accepted ? good : medium)
                            let rect = CGRect(x: CGFloat(b.box.left) * w, y: CGFloat(b.box.top) * h,
                                              width: CGFloat(b.box.width) * w, height: CGFloat(b.box.height) * h)
                            Rectangle()
                                .stroke(color, lineWidth: 3)
                                .frame(width: rect.width, height: rect.height)
                                .offset(x: rect.minX, y: rect.minY)
                            Text(label(b.view) + (b.accepted || b.byUser ? "" : "?") + (b.fromMemory && !b.byUser ? " •" : ""))
                                .font(.system(size: 13, weight: .bold))
                                .foregroundColor(.black)
                                .padding(.horizontal, 3)
                                .background(color)
                                .fixedSize()
                                .offset(x: rect.minX, y: rect.minY >= 18 ? rect.minY - 18 : rect.maxY + 2)
                        }
                    }
                    .contentShape(Rectangle())
                    .gesture(SpatialTapGesture().onEnded { tap in
                        let x = Float(tap.location.x / w)
                        let y = Float(tap.location.y / h)
                        var best: (Int, Float)?
                        for (i, b) in boxes.enumerated() {
                            let mx = max(b.box.width * 0.3, 0.02)
                            let my = max(b.box.height * 0.3, 0.02)
                            if x >= b.box.left - mx, x <= b.box.right + mx, y >= b.box.top - my, y <= b.box.bottom + my {
                                let area = b.box.width * b.box.height
                                if best == nil || area < best!.1 { best = (i, area) }
                            }
                        }
                        if let hit = best { onBoxTap(hit.0) } else { onEmptyTap(x, y) }
                    })
                }
            )
            .id(revision)
    }
}

/// Card picker: tap cards (up to [maxSelect]) and confirm; with maxSelect 1 a tap picks right away.
struct CardPicker: View {
    let rows: [[CardView]]
    let used: Set<String>
    let title: String
    let maxSelect: Int
    let onNotACard: (() -> Void)?
    let onDone: ([String]) -> Void
    let onCancel: () -> Void
    @State private var selected: [String] = []

    var body: some View {
        NavigationStack {
            VStack(spacing: 6) {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    HStack(spacing: 3) {
                        ForEach(row, id: \.code) { c in
                            let isUsed = used.contains(c.code)
                            let isSelected = selected.contains(c.code)
                            Button {
                                if maxSelect <= 1 {
                                    onDone([c.code])
                                } else if isSelected {
                                    selected.removeAll { $0 == c.code }
                                } else if selected.count < maxSelect {
                                    selected.append(c.code)
                                }
                            } label: {
                                VStack(spacing: 0) {
                                    Text(c.rank).font(.system(size: 13, weight: .bold))
                                    Text(c.suit).font(.system(size: 12))
                                }
                                .foregroundColor(c.isRed ? cardRed : .black)
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .background(isUsed ? Color.gray : (isSelected ? gold : ivory))
                                .cornerRadius(4)
                                .overlay(RoundedRectangle(cornerRadius: 4).stroke(isSelected ? Color.black : Color.clear, lineWidth: 2))
                            }
                            .disabled(isUsed)
                        }
                    }
                }
                Spacer()
                HStack {
                    if let notACard = onNotACard {
                        Button(tr("learn_not_a_card"), action: notACard)
                    }
                    Spacer()
                    if maxSelect > 1 {
                        Button(tr("picker_ok", selected.count, maxSelect)) { onDone(selected) }
                            .buttonStyle(.borderedProminent)
                            .tint(gold)
                            .disabled(selected.isEmpty)
                    }
                }
            }
            .padding(12)
            .environment(\.layoutDirection, .leftToRight)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(tr("live_cancel"), action: onCancel) }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
