//! session — Double Ratchet 세션 (vodozemac Olm 기반)
//!
//! 전방향 비밀성·침해후 회복성은 감사 완료된 vodozemac에 위임한다.
//! 우리가 앞서 만든 안전번호(safety_number)는 vodozemac의 신원 공개키로 계산해
//! 중간자 방어에 그대로 쓴다.
//!
//! 교환 흐름:
//!   [수신자 Bob]  신원키 공개 + 일회용키(one-time key) 1개 발행
//!   [발신자 Alice] Bob의 (신원키, 일회용키)로 아웃바운드 세션 생성 → 첫 메시지는 PreKey
//!   [Bob]          첫 PreKey 메시지로 인바운드 세션 생성 → 이후 양방향 래칫 통신

// vodozemac Olm 타입을 앱 계층이 쓰기 쉽게 다시 내보낸다.
pub use vodozemac::olm::{Account, OlmMessage, Session, SessionConfig};

#[cfg(test)]
mod tests {
    use super::*;
    use crate::safety_number;

    /// 수신자 계정을 만들고 (신원키, 일회용키 1개)를 준비한다.
    fn prepare_recipient(
    ) -> (Account, vodozemac::Curve25519PublicKey, vodozemac::Curve25519PublicKey) {
        let mut acc = Account::new();
        acc.generate_one_time_keys(1);
        let identity = acc.curve25519_key();
        let otk = *acc.one_time_keys().values().next().expect("일회용키 1개");
        (acc, identity, otk)
    }

    #[test]
    fn 세션_왕복이_성공한다() {
        let (mut bob, bob_id, bob_otk) = prepare_recipient();

        let alice = Account::new();
        let mut alice_session = alice
            .create_outbound_session(SessionConfig::version_1(), bob_id, bob_otk)
            .expect("아웃바운드 세션");

        // 첫 메시지는 PreKey 형식
        let first = alice_session.encrypt("작전 개시".as_bytes()).expect("암호화");
        let prekey = match first {
            OlmMessage::PreKey(m) => m,
            OlmMessage::Normal(_) => panic!("첫 메시지는 PreKey여야 함"),
        };

        // Bob: 인바운드 세션 생성 + 첫 메시지 복호화
        let result = bob
            .create_inbound_session(SessionConfig::version_1(), alice.curve25519_key(), &prekey)
            .expect("인바운드 세션 생성");
        bob.mark_keys_as_published();
        let mut bob_session = result.session;
        assert_eq!(result.plaintext, "작전 개시".as_bytes());

        // Bob → Alice 답장 (Normal)
        let reply = bob_session.encrypt("수신 양호".as_bytes()).expect("암호화");
        assert_eq!(alice_session.decrypt(&reply).expect("복호화"), "수신 양호".as_bytes());
    }

    #[test]
    fn 여러_메시지_교대_통신이_유지된다() {
        // 래칫이 연속 전진해도 양측 상태가 어긋나지 않는지 확인한다.
        let (mut bob, bob_id, bob_otk) = prepare_recipient();
        let alice = Account::new();
        let mut a_sess = alice
            .create_outbound_session(SessionConfig::version_1(), bob_id, bob_otk)
            .expect("아웃바운드 세션");

        // 핸드셰이크
        let first = a_sess.encrypt(b"m0".as_slice()).expect("암호화");
        let prekey = match first {
            OlmMessage::PreKey(m) => m,
            OlmMessage::Normal(_) => panic!("PreKey 기대"),
        };
        let r = bob
            .create_inbound_session(SessionConfig::version_1(), alice.curve25519_key(), &prekey)
            .unwrap();
        bob.mark_keys_as_published();
        let mut b_sess = r.session;
        assert_eq!(r.plaintext, b"m0");

        // 10회 교대 송수신
        for i in 1..=10u8 {
            let msg = format!("alice->bob #{i}");
            let ct = a_sess.encrypt(msg.as_bytes()).expect("암호화");
            assert_eq!(b_sess.decrypt(&ct).unwrap(), msg.as_bytes());

            let rep = format!("bob->alice #{i}");
            let ct = b_sess.encrypt(rep.as_bytes()).expect("암호화");
            assert_eq!(a_sess.decrypt(&ct).unwrap(), rep.as_bytes());
        }
    }

    #[test]
    fn 같은_평문도_매번_다른_암호문이_된다() {
        // 래칫 전진의 관찰 가능한 신호: 동일 평문이라도 암호문이 매번 달라진다.
        let (_bob, bob_id, bob_otk) = prepare_recipient();
        let alice = Account::new();
        let mut a_sess = alice
            .create_outbound_session(SessionConfig::version_1(), bob_id, bob_otk)
            .expect("아웃바운드 세션");

        let c1 = a_sess.encrypt(b"ping".as_slice()).expect("암호화").to_parts();
        let c2 = a_sess.encrypt(b"ping".as_slice()).expect("암호화").to_parts();
        assert_ne!(c1, c2, "래칫이 전진하면 같은 평문도 다른 암호문이 되어야 함");
    }

    #[test]
    fn vodozemac_신원키로_안전번호를_계산한다() {
        // 앞서 만든 safety_number를 vodozemac 신원 공개키에 그대로 연결한다.
        let alice = Account::new();
        let bob = Account::new();
        let a = alice.curve25519_key().to_bytes();
        let b = bob.curve25519_key().to_bytes();
        assert_eq!(safety_number(&a, &b), safety_number(&b, &a));
        assert_eq!(safety_number(&a, &b).split(' ').count(), 6);
    }
}
