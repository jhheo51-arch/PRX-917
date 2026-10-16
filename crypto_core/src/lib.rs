//! crypto_core — 오프그리드 보안 메신저의 공유 암호 코어
//!
//! iOS(Swift)와 Android(Kotlin)가 이 하나의 코어를 공유한다.
//! 비밀키의 "영구 저장"은 코어 밖(Secure Enclave / Android Keystore)이 담당하고,
//! 이 코어는 "연산"만 책임진다.
//!
//! ⚠️ Double Ratchet(메시지마다 키 전진)은 검증된 라이브러리(libsignal)에 위임한다.
//!    여기서는 그 앞단인 신원·안전번호·X3DH·AEAD 포장만 구현한다.

uniffi::setup_scaffolding!();

pub mod api;
pub mod ffi;
pub mod session;

use rand_core::OsRng;
use x25519_dalek::{StaticSecret, PublicKey};
use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Key, Nonce,
};
use hkdf::Hkdf;
use sha2::{Sha512, Digest};

/// [1] 장기 신원키(IK) 생성 — 기기당 최초 1회.
/// 반환된 비밀키는 즉시 Secure Enclave/Keystore에 저장하고 메모리에서 지울 것.
/// 반환: (비밀키 32B, 공개키 32B)
pub fn generate_identity() -> ([u8; 32], [u8; 32]) {
    let sk = StaticSecret::random_from_rng(OsRng);
    let pk = PublicKey::from(&sk);
    (sk.to_bytes(), pk.to_bytes())
}

/// [3] 안전번호(Safety Number) — 두 공개키를 정렬·해시해 양쪽이 "동일한" 숫자를 얻는다.
/// 두 기기 화면의 숫자가 같으면 중간자(MITM)가 없음이 보장된다.
pub fn safety_number(a_pub: &[u8; 32], b_pub: &[u8; 32]) -> String {
    // 정렬하여 (A,B)와 (B,A)가 같은 결과를 내도록 한다.
    let (lo, hi) = if a_pub <= b_pub { (a_pub, b_pub) } else { (b_pub, a_pub) };
    let mut h = Sha512::new();
    h.update(lo);
    h.update(hi);
    let digest = h.finalize();
    // 앞 15바이트를 5바이트씩 끊어 사람이 대조하기 쉬운 5자리 숫자 3블록으로 만든다.
    digest[..15]
        .chunks(5)
        .map(|c| {
            let n = u64::from_be_bytes([0, 0, 0, c[0], c[1], c[2], c[3], c[4]]);
            format!("{:05}", n % 100_000)
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// [4a] X3DH-lite (발신자) — 세션 루트키를 도출한다.
/// 반환: (루트키 32B, 상대에게 보낼 임시 공개키 32B)
/// 이 루트키를 libsignal Double Ratchet 초기화에 투입한다.
pub fn x3dh_initiator(my_ik_priv: &[u8; 32], their_ik_pub: &[u8; 32]) -> ([u8; 32], [u8; 32]) {
    let ik = StaticSecret::from(*my_ik_priv);
    let eph = StaticSecret::random_from_rng(OsRng);
    let their = PublicKey::from(*their_ik_pub);

    let dh1 = ik.diffie_hellman(&their);   // 인증: 내 신원 ↔ 상대 신원
    let dh2 = eph.diffie_hellman(&their);  // 전방향 비밀성: 임시키 ↔ 상대 신원

    let root = derive_root(dh1.as_bytes(), dh2.as_bytes());
    (root, PublicKey::from(&eph).to_bytes())
}

/// [4b] X3DH-lite (수신자) — 발신자가 보낸 임시 공개키로 동일한 루트키를 도출한다.
pub fn x3dh_responder(
    my_ik_priv: &[u8; 32],
    their_ik_pub: &[u8; 32],
    their_eph_pub: &[u8; 32],
) -> [u8; 32] {
    let ik = StaticSecret::from(*my_ik_priv);
    let their_ik = PublicKey::from(*their_ik_pub);
    let their_eph = PublicKey::from(*their_eph_pub);

    let dh1 = ik.diffie_hellman(&their_ik);   // 발신자의 dh1과 동일 (ECDH 대칭)
    let dh2 = ik.diffie_hellman(&their_eph);  // 발신자의 dh2와 동일

    derive_root(dh1.as_bytes(), dh2.as_bytes())
}

/// dh1 || dh2 를 HKDF-SHA512로 묶어 32B 루트키를 만든다.
fn derive_root(dh1: &[u8; 32], dh2: &[u8; 32]) -> [u8; 32] {
    let mut ikm = [0u8; 64];
    ikm[..32].copy_from_slice(dh1);
    ikm[32..].copy_from_slice(dh2);
    let hk = Hkdf::<Sha512>::new(None, &ikm);
    let mut root = [0u8; 32];
    hk.expand(b"secure-radio/root-key/v1", &mut root)
        .expect("32B는 유효한 HKDF 출력 길이");
    root
}

/// [5a] 메시지 포장 — 래칫이 내준 메시지키(mk)로 AEAD 암호화.
/// aad(연관데이터)엔 헤더(래칫 공개키·카운터)를 넣어 변조·재전송을 암호문에 묶는다.
pub fn seal(mk: &[u8; 32], nonce12: &[u8; 12], plaintext: &[u8], aad: &[u8]) -> Vec<u8> {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(mk));
    cipher
        .encrypt(Nonce::from_slice(nonce12), Payload { msg: plaintext, aad })
        .expect("암호화는 실패하지 않음")
}

/// [5b] 메시지 해제 — 인증 실패(변조/오키/잘못된 aad) 시 None.
pub fn open(mk: &[u8; 32], nonce12: &[u8; 12], ciphertext: &[u8], aad: &[u8]) -> Option<Vec<u8>> {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(mk));
    cipher
        .decrypt(Nonce::from_slice(nonce12), Payload { msg: ciphertext, aad })
        .ok()
}

// ───────────────────────── 테스트 (TDD) ─────────────────────────
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn 안전번호는_순서에_무관하게_동일하다() {
        let (_, a_pub) = generate_identity();
        let (_, b_pub) = generate_identity();
        assert_eq!(safety_number(&a_pub, &b_pub), safety_number(&b_pub, &a_pub));
    }

    #[test]
    fn 안전번호는_결정적이고_형식이_일정하다() {
        let (_, a_pub) = generate_identity();
        let (_, b_pub) = generate_identity();
        let sn = safety_number(&a_pub, &b_pub);
        assert_eq!(sn, safety_number(&a_pub, &b_pub)); // 매번 같은 값
        // "NNNNN NNNNN NNNNN" — 5자리 3블록
        let blocks: Vec<&str> = sn.split(' ').collect();
        assert_eq!(blocks.len(), 3);
        assert!(blocks.iter().all(|b| b.len() == 5 && b.chars().all(|c| c.is_ascii_digit())));
    }

    #[test]
    fn 서로_다른_상대는_다른_안전번호를_갖는다() {
        let (_, a_pub) = generate_identity();
        let (_, b_pub) = generate_identity();
        let (_, c_pub) = generate_identity();
        assert_ne!(safety_number(&a_pub, &b_pub), safety_number(&a_pub, &c_pub));
    }

    #[test]
    fn x3dh_양측이_동일한_루트키를_도출한다() {
        let (a_priv, a_pub) = generate_identity(); // 발신자
        let (b_priv, b_pub) = generate_identity(); // 수신자

        let (root_a, eph_pub) = x3dh_initiator(&a_priv, &b_pub);
        let root_b = x3dh_responder(&b_priv, &a_pub, &eph_pub);

        assert_eq!(root_a, root_b, "양측 루트키가 일치해야 세션 수립 성공");
    }

    #[test]
    fn seal_open_왕복이_성공한다() {
        let mk = [7u8; 32];
        let nonce = [1u8; 12];
        let aad = b"header:ctr=0";
        let msg = "작전 지역 이상 무".as_bytes();

        let ct = seal(&mk, &nonce, msg, aad);
        assert_ne!(&ct[..msg.len().min(ct.len())], msg); // 평문이 그대로 노출되지 않음
        assert_eq!(open(&mk, &nonce, &ct, aad).as_deref(), Some(msg));
    }

    #[test]
    fn 변조된_암호문은_거부된다() {
        let mk = [7u8; 32];
        let nonce = [1u8; 12];
        let aad = b"header";
        let mut ct = seal(&mk, &nonce, b"hello", aad);
        ct[0] ^= 0x01; // 1비트 변조
        assert!(open(&mk, &nonce, &ct, aad).is_none());
    }

    #[test]
    fn 다른_aad로는_열리지_않는다() {
        let mk = [7u8; 32];
        let nonce = [1u8; 12];
        let ct = seal(&mk, &nonce, b"hello", b"header:ctr=0");
        assert!(open(&mk, &nonce, &ct, b"header:ctr=1").is_none());
    }

    #[test]
    fn 잘못된_키로는_열리지_않는다() {
        let nonce = [1u8; 12];
        let ct = seal(&[7u8; 32], &nonce, b"hello", b"h");
        assert!(open(&[8u8; 32], &nonce, &ct, b"h").is_none());
    }
}
