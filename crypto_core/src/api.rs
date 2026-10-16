//! api — 앱(iOS/Android)이 직접 호출하는 공개 계층.
//!
//! 설계 원칙:
//!  · 단순 타입(String, Vec<u8>, [u8;32])만 노출 → 다음 단계 UniFFI 바인딩이 쉬움
//!  · 비밀 상태(Account/Session)는 "암호화된 pickle 문자열"로 저장/복원
//!    → 그 문자열을 iOS Keychain / Android Keystore에 넣어두면 재시작 후에도 세션 유지
//!  · 전송 계층(Wi-Fi Direct/BLE)은 여기서 만든 "wire 문자열"만 그대로 실어 나르면 됨

use vodozemac::olm::{
    Account, AccountPickle, Message, OlmMessage, PreKeyMessage, Session, SessionConfig,
    SessionPickle,
};
use vodozemac::Curve25519PublicKey;

use crate::safety_number;

/// 세션 수립 시 양측이 합의할 Olm 버전. 발신/수신이 반드시 동일해야 한다.
fn config() -> SessionConfig {
    SessionConfig::version_1()
}

// ── wire 포맷: 전송 계층에 실어 나르는 한 줄 문자열 ───────────────
// "P<base64>" = 세션 시작용 PreKey 메시지, "N<base64>" = 일반 메시지
fn encode_wire(m: OlmMessage) -> String {
    match m {
        OlmMessage::PreKey(p) => format!("P{}", p.to_base64()),
        OlmMessage::Normal(n) => format!("N{}", n.to_base64()),
    }
}

fn decode_wire(s: &str) -> Result<OlmMessage, String> {
    let mut chars = s.chars();
    let tag = chars.next().ok_or("빈 메시지")?;
    let body = chars.as_str();
    match tag {
        'P' => Ok(OlmMessage::PreKey(
            PreKeyMessage::from_base64(body).map_err(|e| e.to_string())?,
        )),
        'N' => Ok(OlmMessage::Normal(
            Message::from_base64(body).map_err(|e| e.to_string())?,
        )),
        _ => Err("알 수 없는 메시지 태그".into()),
    }
}

/// 내 장기 신원 — 기기당 하나. 생성 후 save()로 봉인해 보관한다.
pub struct Identity {
    account: Account,
}

impl Identity {
    /// 신규 신원 생성(최초 1회).
    pub fn generate() -> Self {
        Self { account: Account::new() }
    }

    /// 보관해 둔 암호화 pickle에서 복원. pickle_key가 틀리면 실패.
    pub fn restore(encrypted: &str, pickle_key: &[u8; 32]) -> Result<Self, String> {
        let pickle = AccountPickle::from_encrypted(encrypted, pickle_key).map_err(|e| e.to_string())?;
        Ok(Self { account: Account::from_pickle(pickle) })
    }

    /// 암호화 pickle 문자열로 봉인. 이 문자열을 Keychain/Keystore에 저장.
    pub fn save(&self, pickle_key: &[u8; 32]) -> String {
        self.account.pickle().encrypt(pickle_key)
    }

    /// 내 신원 공개키(base64) — 상대에게 QR로 전달할 값.
    pub fn public_key(&self) -> String {
        self.account.curve25519_key().to_base64()
    }

    /// 상대 공개키와의 안전번호 — 두 기기가 같은 값이면 중간자 없음.
    pub fn safety_number_with(&self, their_pub_b64: &str) -> Result<String, String> {
        let theirs = Curve25519PublicKey::from_base64(their_pub_b64).map_err(|e| e.to_string())?;
        let mine = self.account.curve25519_key().to_bytes();
        Ok(safety_number(&mine, &theirs.to_bytes()))
    }

    /// 일회용 프리키 1개 발행 → base64 반환. (신원키와 함께 상대에게 전달)
    pub fn generate_prekey(&mut self) -> Result<String, String> {
        let _ = self.account.generate_one_time_keys(1);
        let otk = self
            .account
            .one_time_keys()
            .values()
            .next()
            .copied()
            .ok_or("프리키 생성 실패")?;
        Ok(otk.to_base64())
    }

    /// 발행한 프리키가 상대에게 전달돼 소비된 뒤 호출 → 내부 상태 정리.
    pub fn mark_published(&mut self) {
        self.account.mark_keys_as_published();
    }

    /// [발신] 상대의 (신원키, 프리키)로 세션을 열고 첫 메시지까지 암호화.
    /// 반환: (세션, 상대에게 보낼 wire 문자열)
    pub fn start_session(
        &self,
        their_id_b64: &str,
        their_prekey_b64: &str,
        first_plaintext: &[u8],
    ) -> Result<(SecureSession, String), String> {
        let their_id = Curve25519PublicKey::from_base64(their_id_b64).map_err(|e| e.to_string())?;
        let their_pk = Curve25519PublicKey::from_base64(their_prekey_b64).map_err(|e| e.to_string())?;

        let mut session = self
            .account
            .create_outbound_session(config(), their_id, their_pk)
            .map_err(|e| e.to_string())?;
        let wire = encode_wire(session.encrypt(first_plaintext).map_err(|e| e.to_string())?);
        Ok((SecureSession { session }, wire))
    }

    /// [수신] 상대의 신원키 + 첫 PreKey wire로 세션을 열고 첫 메시지를 복호화.
    /// 반환: (세션, 평문)
    pub fn accept_session(
        &mut self,
        their_id_b64: &str,
        wire: &str,
    ) -> Result<(SecureSession, Vec<u8>), String> {
        let their_id = Curve25519PublicKey::from_base64(their_id_b64).map_err(|e| e.to_string())?;
        let prekey = match decode_wire(wire)? {
            OlmMessage::PreKey(p) => p,
            OlmMessage::Normal(_) => return Err("세션 시작은 PreKey 메시지여야 함".into()),
        };
        let result = self
            .account
            .create_inbound_session(config(), their_id, &prekey)
            .map_err(|e| e.to_string())?;
        self.account.mark_keys_as_published();
        Ok((SecureSession { session: result.session }, result.plaintext))
    }

    /// [발신·FFI용] 세션만 열고 첫 메시지는 암호화하지 않는다.
    /// 반환된 세션의 첫 encrypt() 결과가 상대에게 보낼 PreKey wire가 된다.
    pub fn outbound_session(
        &self,
        their_id_b64: &str,
        their_prekey_b64: &str,
    ) -> Result<SecureSession, String> {
        let their_id = Curve25519PublicKey::from_base64(their_id_b64).map_err(|e| e.to_string())?;
        let their_pk = Curve25519PublicKey::from_base64(their_prekey_b64).map_err(|e| e.to_string())?;
        let session = self
            .account
            .create_outbound_session(config(), their_id, their_pk)
            .map_err(|e| e.to_string())?;
        Ok(SecureSession { session })
    }
}

/// 상대 1명과의 암호화 세션(Double Ratchet). save()로 재시작에 대비해 보관한다.
pub struct SecureSession {
    session: Session,
}

impl SecureSession {
    /// 평문 → wire 문자열(전송 계층에 그대로 실어 보냄).
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<String, String> {
        Ok(encode_wire(self.session.encrypt(plaintext).map_err(|e| e.to_string())?))
    }

    /// wire 문자열 → 평문. 변조·순서오류 시 Err.
    pub fn decrypt(&mut self, wire: &str) -> Result<Vec<u8>, String> {
        self.session.decrypt(&decode_wire(wire)?).map_err(|e| e.to_string())
    }

    /// 세션 상태를 암호화 pickle로 봉인(앱 종료 대비).
    pub fn save(&self, pickle_key: &[u8; 32]) -> String {
        self.session.pickle().encrypt(pickle_key)
    }

    /// 보관해 둔 세션 복원.
    pub fn restore(encrypted: &str, pickle_key: &[u8; 32]) -> Result<Self, String> {
        let pickle = SessionPickle::from_encrypted(encrypted, pickle_key).map_err(|e| e.to_string())?;
        Ok(Self { session: Session::from_pickle(pickle) })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: [u8; 32] = [42u8; 32];

    /// 전체 흐름: Bob 프리키 발행 → Alice 세션 시작 → Bob 수락 → 양방향 통신
    #[test]
    fn 전체_흐름이_동작한다() {
        let mut bob = Identity::generate();
        let bob_id = bob.public_key();
        let bob_pk = bob.generate_prekey().unwrap();

        let alice = Identity::generate();
        let (mut a_sess, wire) = alice.start_session(&bob_id, &bob_pk, "작전 개시".as_bytes()).unwrap();

        let (mut b_sess, first) = bob.accept_session(&alice.public_key(), &wire).unwrap();
        assert_eq!(first, "작전 개시".as_bytes());

        // 양방향 추가 통신
        let w = b_sess.encrypt("수신 양호".as_bytes()).unwrap();
        assert_eq!(a_sess.decrypt(&w).unwrap(), "수신 양호".as_bytes());
        let w = a_sess.encrypt("좌표 송신".as_bytes()).unwrap();
        assert_eq!(b_sess.decrypt(&w).unwrap(), "좌표 송신".as_bytes());
    }

    /// 양측이 계산한 안전번호가 일치한다(중간자 방어).
    #[test]
    fn 양측_안전번호가_일치한다() {
        let a = Identity::generate();
        let b = Identity::generate();
        assert_eq!(
            a.safety_number_with(&b.public_key()).unwrap(),
            b.safety_number_with(&a.public_key()).unwrap()
        );
    }

    /// 신원 저장→복원 후에도 공개키가 동일하다.
    #[test]
    fn 신원_저장_복원이_보존된다() {
        let id = Identity::generate();
        let saved = id.save(&KEY);
        let restored = Identity::restore(&saved, &KEY).unwrap();
        assert_eq!(id.public_key(), restored.public_key());
    }

    /// 틀린 pickle_key로는 복원할 수 없다.
    #[test]
    fn 틀린_키로는_복원_실패() {
        let saved = Identity::generate().save(&KEY);
        assert!(Identity::restore(&saved, &[0u8; 32]).is_err());
    }

    /// 세션을 저장·복원해도("앱 재시작") 대화가 이어진다.
    #[test]
    fn 세션_저장_복원_후_대화가_이어진다() {
        let mut bob = Identity::generate();
        let bob_pk = bob.generate_prekey().unwrap();
        let alice = Identity::generate();

        let (mut a_sess, wire) = alice.start_session(&bob.public_key(), &bob_pk, b"m0").unwrap();
        let (b_sess, first) = bob.accept_session(&alice.public_key(), &wire).unwrap();
        assert_eq!(first, b"m0");

        // Bob 세션을 저장했다가(앱 종료) 복원(앱 재시작)
        let saved = b_sess.save(&KEY);
        let mut b_restored = SecureSession::restore(&saved, &KEY).unwrap();

        // 복원된 세션이 이후 메시지를 정상 복호화
        let w = a_sess.encrypt("재시작 후 수신".as_bytes()).unwrap();
        assert_eq!(b_restored.decrypt(&w).unwrap(), "재시작 후 수신".as_bytes());
    }

    /// outbound_session(첫 암호화 분리) 흐름도 정상 동작한다.
    #[test]
    fn outbound_session_흐름이_동작한다() {
        let mut bob = Identity::generate();
        let bob_pk = bob.generate_prekey().unwrap();
        let alice = Identity::generate();

        // 세션만 열고, 첫 encrypt가 PreKey wire가 된다
        let mut a_sess = alice.outbound_session(&bob.public_key(), &bob_pk).unwrap();
        let wire = a_sess.encrypt("첫 교신".as_bytes()).unwrap();

        let (_b_sess, first) = bob.accept_session(&alice.public_key(), &wire).unwrap();
        assert_eq!(first, "첫 교신".as_bytes());
    }

    /// 변조된 wire는 거부된다.
    #[test]
    fn 변조된_wire는_거부된다() {
        let mut bob = Identity::generate();
        let bob_pk = bob.generate_prekey().unwrap();
        let alice = Identity::generate();
        let (_a, wire) = alice.start_session(&bob.public_key(), &bob_pk, b"hi").unwrap();

        let mut bad = wire.into_bytes();
        *bad.last_mut().unwrap() ^= 0x01; // 마지막 바이트 변조
        let bad = String::from_utf8(bad).unwrap();
        assert!(bob.accept_session(&alice.public_key(), &bad).is_err());
    }
}
