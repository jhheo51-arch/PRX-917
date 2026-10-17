import SwiftUI

// 전술 팔레트 (국방색 섀시 + 앰버 OLED)
extension Color {
    static let scr    = Color(red: 0.047, green: 0.059, blue: 0.039)
    static let scrFg  = Color(red: 0.925, green: 0.788, blue: 0.471)
    static let scrDim = Color(red: 0.561, green: 0.541, blue: 0.369)
    static let scrGrn = Color(red: 0.561, green: 0.682, blue: 0.353)
    static let scrRed = Color(red: 0.831, green: 0.439, blue: 0.247)
    static let scrLine = Color(red: 0.165, green: 0.176, blue: 0.114)
}

struct RootView: View {
    @EnvironmentObject var m: RadioModel

    var body: some View {
        ZStack {
            Color.scr.ignoresSafeArea()
            switch m.screen {
            case .lock:      LockView()
            case .channel:   ChannelView()
            case .discovery: DiscoveryView()
            case .safety:    SafetyView()
            case .chat:      ChatView()
            case .settings:  SettingsView()
            }
        }
        .foregroundColor(.scrFg)
        .font(.system(.body, design: .monospaced))
    }
}

private struct LockView: View {
    @EnvironmentObject var m: RadioModel
    var body: some View {
        VStack(spacing: 14) {
            Spacer()
            Text("🙂").font(.system(size: 54))
            Text("PRX-917 잠금").font(.title2.bold())
            Text("얼굴/지문으로 신원키 잠금 해제")
                .font(.caption).foregroundColor(.scrDim)
            Button(m.unlocking ? "인증 중…" : "생체인증으로 잠금 해제") {
                Task { await m.unlock() }   // LAContext 인증 → Keychain 신원 로드 후에만 진입
            }
                .buttonStyle(Primary())
                .disabled(m.unlocking)
            if let err = m.unlockError {
                Text(err).font(.caption).foregroundColor(.scrRed)
                    .multilineTextAlignment(.center)
            }
            Spacer()
            Text("생체정보는 기기를 벗어나지 않습니다")
                .font(.caption2).foregroundColor(.scrDim)
        }.padding(24)
    }
}

private struct ChannelView: View {
    @EnvironmentObject var m: RadioModel
    let cols = [GridItem(.flexible()), GridItem(.flexible())]
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("통신 채널").font(.title2.bold())
                    Text(m.callsign).font(.caption).foregroundColor(.scrDim)
                }
                Spacer()
                Button { m.goSettings() } label: { Text("⚙").font(.title2) }
                    .accessibilityLabel("설정")
            }
            LazyVGrid(columns: cols, spacing: 10) {
                ForEach(m.channels, id: \.0) { ch in
                    Button { m.selectChannel(ch.0) } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("CH \(ch.0)").font(.title3.bold())
                            Text("\(ch.1) MHz").font(.caption2).foregroundColor(.scrDim)
                        }.frame(maxWidth: .infinity, alignment: .leading).padding(14)
                        .background(m.channel == ch.0 ? Color.scrFg.opacity(0.2) : Color.scrLine)
                        .cornerRadius(12)
                    }.foregroundColor(.scrFg)
                }
            }
            Spacer()
            Text("같은 채널끼리만 교신합니다").font(.caption2).foregroundColor(.scrDim)
        }.padding(20)
    }
}

private struct DiscoveryView: View {
    @EnvironmentObject var m: RadioModel
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("주변 국(局) 탐색 · CH \(m.channel)").font(.title3.bold())
            if m.discovered.isEmpty {
                Spacer()
                HStack { ProgressView().tint(.scrFg); Text("교신 가능한 국 수신 중…").foregroundColor(.scrDim) }
                    .frame(maxWidth: .infinity)
                Spacer()
            } else {
                ForEach(m.discovered, id: \.self) { name in
                    HStack {
                        Circle().fill(Color.scrGrn).frame(width: 9, height: 9)
                        Text(name).bold()
                        Spacer()
                        Text(m.linked ? "연결됨" : "발견").font(.caption).foregroundColor(.scrDim)
                    }.padding(12).background(Color.scrLine).cornerRadius(12)
                }
                Spacer()
            }
            Button("← 채널 변경") { m.screen = .channel }.buttonStyle(Ghost())
        }.padding(20)
    }
}

private struct SafetyView: View {
    @EnvironmentObject var m: RadioModel
    var body: some View {
        VStack(spacing: 14) {
            Text("안전번호 확인").font(.title2.bold()).frame(maxWidth: .infinity, alignment: .leading)
            VStack(spacing: 8) {
                Text("SAFETY NUMBER · \(m.peerName)").font(.caption2).foregroundColor(.scrDim)
                Text(m.safetyNumber.isEmpty ? "— — —" : m.safetyNumber)
                    .font(.system(.title3, design: .monospaced)).bold()
                    .multilineTextAlignment(.center)
            }.frame(maxWidth: .infinity).padding(20).background(Color.scrLine).cornerRadius(14)
            Text("상대 기기 화면의 숫자와 완전히 같나요?\n같으면 중간자(도청자)가 없습니다.")
                .font(.callout).multilineTextAlignment(.center)
            HStack {
                Button("불일치") { m.rejectSafety() }.buttonStyle(Danger())
                Button("일치 · 확인") { m.confirmSafety() }.buttonStyle(Primary())
            }
            Spacer()
        }.padding(20)
    }
}

private struct ChatView: View {
    @EnvironmentObject var m: RadioModel
    @State private var draft = ""
    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button { m.screen = .discovery } label: { Text("←") }
                VStack(alignment: .leading) {
                    Text(m.peerName).bold()
                    Text("CH \(m.channel) · 암호화됨").font(.caption2).foregroundColor(.scrDim)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(m.linked ? "🔒 E2E" : "🔴 끊김").font(.caption).foregroundColor(m.linked ? .scrGrn : .scrRed)
                    Text(m.verifiedPeer ? "✓ 검증됨" : "미검증").font(.caption2)
                        .foregroundColor(m.verifiedPeer ? .scrGrn : .scrRed)
                }
            }.padding(12).background(Color.scrLine)

            ScrollView {
                VStack(spacing: 8) {
                    ForEach(m.messages) { msg in
                        HStack {
                            if msg.mine { Spacer() }
                            VStack(alignment: msg.mine ? .trailing : .leading, spacing: 2) {
                                Text(msg.text)
                                    .padding(10)
                                    .background(msg.mine ? Color.scrFg : Color.scrLine)
                                    .foregroundColor(msg.mine ? Color.scr : Color.scrFg)
                                    .cornerRadius(12)
                                if let d = msg.delivery { DeliveryLabel(state: d) }
                            }
                            if !msg.mine { Spacer() }
                        }
                    }
                }.padding(14)
            }

            HStack(spacing: 8) {
                TextField("메시지 입력…", text: $draft)
                    .textFieldStyle(.plain).padding(10)
                    .background(Color.scrLine).cornerRadius(10).foregroundColor(.scrFg)
                Button("전송") { m.send(draft); draft = "" }
                    .buttonStyle(Primary()).disabled(!m.linked)
            }.padding(10).background(Color.scrLine.opacity(0.5))
        }
    }
}

/// 내 메시지 전달 상태 (PRD F-T6)
private struct DeliveryLabel: View {
    let state: DeliveryState
    var body: some View {
        switch state {
        case .pending:   Text("⏳").font(.caption2).foregroundColor(.scrDim)
        case .delivered: Text("✓ 전달").font(.caption2).foregroundColor(.scrGrn)
        case .failed:    Text("! 미전달").font(.caption2).foregroundColor(.scrRed)
        }
    }
}

private struct SettingsView: View {
    @EnvironmentObject var m: RadioModel
    @State private var draft = ""
    @State private var error: String?
    @State private var saved = false
    @State private var confirmClear = false
    @State private var cleared = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    Button { m.goChannel() } label: { Text("←") }
                        .accessibilityLabel("돌아가기")
                    Text("설정").font(.title2.bold())
                    Spacer()
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("내 콜사인").font(.caption).foregroundColor(.scrDim)
                    HStack(spacing: 8) {
                        TextField("예: ALPHA-1", text: $draft)
                            .textInputAutocapitalization(.characters)
                            .disableAutocorrection(true)   // iOS 15 호환
                            .textFieldStyle(.plain).padding(10)
                            .background(Color.scrLine).cornerRadius(10)
                        Button("저장") {
                            error = m.setCallsign(draft)
                            saved = error == nil
                            if saved { draft = m.callsign }
                        }
                        .buttonStyle(Primary()).frame(width: 90)
                    }
                    Text("영문 대문자·숫자·하이픈, 2~12자. 바꾸면 현재 연결이 끊깁니다.")
                        .font(.caption2).foregroundColor(.scrDim)
                    if let error {
                        Text(error).font(.caption).foregroundColor(.scrRed)
                    }
                }.padding(14).background(Color.scrLine.opacity(0.5)).cornerRadius(12)

                VStack(spacing: 0) {
                    kv("기본 채널", "CH \(m.channel)")
                    kv("생체인증 잠금", "필수 · Keychain 바인딩")
                    Toggle("메시지 24h 후 자동 삭제", isOn: $m.autoDelete)
                        .tint(.scrGrn).padding(.vertical, 10)
                    Toggle("수신 알림음", isOn: $m.soundOn)
                        .tint(.scrGrn).padding(.vertical, 10)
                    kv("암호 스택", "X25519 · ChaCha20")
                    kv("버전", appVersion)
                }.padding(.horizontal, 14).background(Color.scrLine.opacity(0.5)).cornerRadius(12)

                VStack(alignment: .leading, spacing: 8) {
                    Text("검증한 상대 기록").font(.caption).foregroundColor(.scrDim)
                    Text("안전번호를 확인한 상대는 다음 연결 때 확인을 건너뜁니다. 지우면 모두 다시 확인해야 합니다.")
                        .font(.caption2).foregroundColor(.scrDim)
                    if confirmClear {
                        Text("정말 지울까요? 되돌릴 수 없습니다.").font(.caption).foregroundColor(.scrRed)
                        HStack(spacing: 10) {
                            Button("취소") { confirmClear = false }.buttonStyle(Ghost())
                            Button("지우기") {
                                m.clearVerifiedPeers(); confirmClear = false; cleared = true
                            }.buttonStyle(Danger())
                        }
                    } else {
                        Button("검증한 상대 기록 지우기") { confirmClear = true; cleared = false }
                            .buttonStyle(Danger())
                    }
                    if cleared {
                        Text("기록을 지웠습니다.").font(.caption).foregroundColor(.scrGrn)
                    }
                }.padding(14).background(Color.scrLine.opacity(0.5)).cornerRadius(12)
            }.padding(20)
        }
        .onAppear { draft = m.callsign; error = nil }
    }

    private var appVersion: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1"
        return "\(v) · debug"
    }

    private func kv(_ k: String, _ v: String) -> some View {
        HStack {
            Text(k)
            Spacer()
            Text(v).foregroundColor(.scrDim)
        }.font(.callout).padding(.vertical, 10)
    }
}

// 버튼 스타일
private struct Primary: ButtonStyle {
    func makeBody(configuration c: Configuration) -> some View {
        c.label.font(.headline).padding(.vertical, 12).padding(.horizontal, 16)
            .frame(maxWidth: .infinity).background(Color.scrFg).foregroundColor(.scr).cornerRadius(11)
            .opacity(c.isPressed ? 0.8 : 1)
    }
}
private struct Ghost: ButtonStyle {
    func makeBody(configuration c: Configuration) -> some View {
        c.label.font(.headline).padding(.vertical, 12).padding(.horizontal, 16)
            .frame(maxWidth: .infinity).overlay(RoundedRectangle(cornerRadius: 11).stroke(Color.scrLine))
            .foregroundColor(.scrFg).opacity(c.isPressed ? 0.7 : 1)
    }
}
private struct Danger: ButtonStyle {
    func makeBody(configuration c: Configuration) -> some View {
        c.label.font(.headline).padding(.vertical, 12).padding(.horizontal, 16)
            .frame(maxWidth: .infinity).overlay(RoundedRectangle(cornerRadius: 11).stroke(Color.scrRed))
            .foregroundColor(.scrRed).opacity(c.isPressed ? 0.7 : 1)
    }
}
