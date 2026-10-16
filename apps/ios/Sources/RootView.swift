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
            case .settings:  ChannelView()
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
            Button("얼굴 인식으로 잠금 해제") { m.unlock() }   // 실제: LAContext 생체인증 연결
                .buttonStyle(Primary())
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
            Text("통신 채널").font(.title2.bold())
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
                Text(m.linked ? "🔒 E2E" : "🔴 끊김").font(.caption).foregroundColor(m.linked ? .scrGrn : .scrRed)
            }.padding(12).background(Color.scrLine)

            ScrollView {
                VStack(spacing: 8) {
                    ForEach(m.messages) { msg in
                        HStack {
                            if msg.mine { Spacer() }
                            Text(msg.text)
                                .padding(10)
                                .background(msg.mine ? Color.scrFg : Color.scrLine)
                                .foregroundColor(msg.mine ? Color.scr : Color.scrFg)
                                .cornerRadius(12)
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
