import Foundation
import LocalAuthentication
import Security

/// 신원키 영구저장 (Android IdentityStore 대응).
/// 실제 비밀값은 crypto_core 가 pickle_key 로 암호화하고(pickle 문자열),
/// - pickle_key(32B): Keychain · WhenUnlockedThisDeviceOnly · `.userPresence` 접근제어 → 생체(또는 기기 암호) 인증 없이는 꺼낼 수 없다.
/// - pickle: 앱 컨테이너 파일 · Data Protection complete · 백업 제외 (pickle_key 없이는 복호화 불가).
/// 조회는 호출측이 이미 인증한 LAContext 를 넘겨 받아 재프롬프트 없이 수행한다.
enum IdentityStore {
    enum StoreError: LocalizedError {
        case keychain(OSStatus)
        case accessControl
        case random

        var errorDescription: String? {
            switch self {
            case .keychain(let s):
                let msg = SecCopyErrorMessageString(s, nil) as String? ?? "알 수 없는 오류"
                return "보안저장소 오류(\(s)): \(msg)"
            case .accessControl: return "보안저장소 접근제어 생성 실패"
            case .random: return "난수 생성 실패"
            }
        }
    }

    private static let service = "app.prx917.messenger.identity"
    private static let account = "pickle_key"

    private static var pickleURL: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return dir.appendingPathComponent("identity.pickle")
    }

    /// 저장된 신원을 복원하거나, 없으면 새로 생성해 저장한다.
    /// 인증 실패·취소 등 Keychain 오류는 그대로 던진다(기존 신원을 덮어쓰지 않도록).
    static func loadOrCreate(context: LAContext) throws -> Identity {
        if let key = try readKey(context: context),
           let pickle = try? String(contentsOf: pickleURL, encoding: .utf8),
           let id = try? Identity.restore(encrypted: pickle, pickleKey: key) {
            return id
        }
        // 저장 없음 또는 손상 → 재생성
        return try create(context: context)
    }

    /// 신원키 재생성 — 기존 저장 삭제 후 새로 생성·저장.
    static func reset(context: LAContext) throws -> Identity {
        VerifiedPeers.clear()   // 내 키가 바뀌므로 검증 기록도 무효 (PRD F-T3)
        return try create(context: context)
    }

    // MARK: - 내부

    private static func create(context: LAContext) throws -> Identity {
        var key = Data(count: 32)
        let rc = key.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard rc == errSecSuccess else { throw StoreError.random }

        let id = Identity.generate()
        let pickle = try id.save(pickleKey: key)

        try writePickle(pickle)
        try writeKey(key, context: context)
        return id
    }

    private static func readKey(context: LAContext) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
            kSecUseAuthenticationContext as String: context,
        ]
        var out: CFTypeRef?
        let s = SecItemCopyMatching(query as CFDictionary, &out)
        switch s {
        case errSecSuccess:
            guard let d = out as? Data, d.count == 32 else { return nil }
            return d
        case errSecItemNotFound:
            return nil
        default:
            throw StoreError.keychain(s)
        }
    }

    private static func writeKey(_ key: Data, context: LAContext) throws {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let del = SecItemDelete(base as CFDictionary)
        guard del == errSecSuccess || del == errSecItemNotFound else { throw StoreError.keychain(del) }

        // 생체 우선, 미등록/실패 시 기기 암호로 대체(.userPresence). 기기 밖 이동·백업 불가.
        guard let ac = SecAccessControlCreateWithFlags(
            nil, kSecAttrAccessibleWhenUnlockedThisDeviceOnly, .userPresence, nil
        ) else { throw StoreError.accessControl }

        var add = base
        add[kSecValueData as String] = key
        add[kSecAttrAccessControl as String] = ac
        add[kSecUseAuthenticationContext as String] = context
        let s = SecItemAdd(add as CFDictionary, nil)
        guard s == errSecSuccess else { throw StoreError.keychain(s) }
    }

    private static func writePickle(_ pickle: String) throws {
        var url = pickleURL
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(pickle.utf8).write(to: url, options: [.atomic, .completeFileProtection])
        var rv = URLResourceValues()
        rv.isExcludedFromBackup = true
        try url.setResourceValues(rv)
    }
}
