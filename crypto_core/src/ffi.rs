//! ffi — UniFFI 노출 계층. 검증된 api.rs를 iOS(Swift)/Android(Kotlin)가 쓰도록 감싼다.
//!
//! 변환 규칙:
//!  · 키: [u8;32] → Vec<u8> (길이 검증)
//!  · 가변 메서드: Mutex 내부가변성(&mut self 불가 → &self + lock)
//!  · 오류: Result<_,String> → CoreError 열거형
//!
//! 세션 생성 흐름(객체+데이터 동반 반환을 피하려고 둘로 나눔):
//!  · 발신: open_session() → SecureSession, 첫 encrypt()가 상대에게 보낼 PreKey wire
//!  · 수신: accept_session() → SecureSession (첫 평문은 take_first_message()로 꺼냄)

use std::sync::{Arc, Mutex};

use crate::api;

/// FFI 경계로 넘기는 오류.
#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("암호 처리 실패: {0}")]
    Crypto(String),
    #[error("pickle 키는 32바이트여야 합니다")]
    BadKeyLength,
}

fn key32(bytes: &[u8]) -> Result<[u8; 32], CoreError> {
    bytes.try_into().map_err(|_| CoreError::BadKeyLength)
}

/// 내 장기 신원.
#[derive(uniffi::Object)]
pub struct Identity {
    inner: Mutex<api::Identity>,
}

#[uniffi::export]
impl Identity {
    /// 신규 신원 생성.
    #[uniffi::constructor]
    pub fn generate() -> Arc<Self> {
        Arc::new(Self { inner: Mutex::new(api::Identity::generate()) })
    }

    /// 암호화 pickle에서 복원.
    #[uniffi::constructor]
    pub fn restore(encrypted: String, pickle_key: Vec<u8>) -> Result<Arc<Self>, CoreError> {
        let k = key32(&pickle_key)?;
        let id = api::Identity::restore(&encrypted, &k).map_err(CoreError::Crypto)?;
        Ok(Arc::new(Self { inner: Mutex::new(id) }))
    }

    /// 암호화 pickle 문자열(Keychain/Keystore에 저장).
    pub fn save(&self, pickle_key: Vec<u8>) -> Result<String, CoreError> {
        let k = key32(&pickle_key)?;
        Ok(self.inner.lock().unwrap().save(&k))
    }

    /// 내 신원 공개키(base64).
    pub fn public_key(&self) -> String {
        self.inner.lock().unwrap().public_key()
    }

    /// 상대 공개키와의 안전번호(중간자 방어용).
    pub fn safety_number_with(&self, their_pub_b64: String) -> Result<String, CoreError> {
        self.inner
            .lock()
            .unwrap()
            .safety_number_with(&their_pub_b64)
            .map_err(CoreError::Crypto)
    }

    /// 일회용 프리키 1개 발행(base64).
    pub fn generate_prekey(&self) -> Result<String, CoreError> {
        self.inner.lock().unwrap().generate_prekey().map_err(CoreError::Crypto)
    }

    /// 프리키 소비 후 내부 상태 정리.
    pub fn mark_published(&self) {
        self.inner.lock().unwrap().mark_published();
    }

    /// [발신] 상대(신원키, 프리키)로 세션 생성. 첫 encrypt()가 PreKey wire.
    pub fn open_session(
        &self,
        their_id_b64: String,
        their_prekey_b64: String,
    ) -> Result<Arc<SecureSession>, CoreError> {
        let sess = self
            .inner
            .lock()
            .unwrap()
            .outbound_session(&their_id_b64, &their_prekey_b64)
            .map_err(CoreError::Crypto)?;
        Ok(Arc::new(SecureSession::wrap(sess, None)))
    }

    /// [수신] 상대 신원키 + 첫 PreKey wire로 세션 생성.
    /// 첫 평문은 반환 세션의 take_first_message()로 꺼낸다.
    pub fn accept_session(
        &self,
        their_id_b64: String,
        wire: String,
    ) -> Result<Arc<SecureSession>, CoreError> {
        let (sess, plaintext) = self
            .inner
            .lock()
            .unwrap()
            .accept_session(&their_id_b64, &wire)
            .map_err(CoreError::Crypto)?;
        Ok(Arc::new(SecureSession::wrap(sess, Some(plaintext))))
    }
}

/// 상대 1명과의 암호화 세션.
#[derive(uniffi::Object)]
pub struct SecureSession {
    inner: Mutex<api::SecureSession>,
    first: Mutex<Option<Vec<u8>>>,
}

impl SecureSession {
    fn wrap(inner: api::SecureSession, first: Option<Vec<u8>>) -> Self {
        Self { inner: Mutex::new(inner), first: Mutex::new(first) }
    }
}

#[uniffi::export]
impl SecureSession {
    /// 수신 세션의 첫 평문을 한 번 꺼낸다(이후 None).
    pub fn take_first_message(&self) -> Option<Vec<u8>> {
        self.first.lock().unwrap().take()
    }

    /// 평문 → wire 문자열(전송 계층에 실어 보냄).
    pub fn encrypt(&self, plaintext: Vec<u8>) -> Result<String, CoreError> {
        self.inner.lock().unwrap().encrypt(&plaintext).map_err(CoreError::Crypto)
    }

    /// wire 문자열 → 평문.
    pub fn decrypt(&self, wire: String) -> Result<Vec<u8>, CoreError> {
        self.inner.lock().unwrap().decrypt(&wire).map_err(CoreError::Crypto)
    }

    /// 세션 상태를 암호화 pickle로 봉인.
    pub fn save(&self, pickle_key: Vec<u8>) -> Result<String, CoreError> {
        let k = key32(&pickle_key)?;
        Ok(self.inner.lock().unwrap().save(&k))
    }

    /// 보관해 둔 세션 복원.
    #[uniffi::constructor]
    pub fn restore(encrypted: String, pickle_key: Vec<u8>) -> Result<Arc<Self>, CoreError> {
        let k = key32(&pickle_key)?;
        let s = api::SecureSession::restore(&encrypted, &k).map_err(CoreError::Crypto)?;
        Ok(Arc::new(SecureSession::wrap(s, None)))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const KEY: [u8; 32] = [9u8; 32];

    #[test]
    fn ffi_전체_흐름이_동작한다() {
        let bob = Identity::generate();
        let bob_pk = bob.generate_prekey().unwrap();
        let alice = Identity::generate();

        // 발신: 세션 열고 첫 메시지 암호화
        let a_sess = alice
            .open_session(bob.public_key(), bob_pk)
            .unwrap();
        let wire = a_sess.encrypt("작전 개시".as_bytes().to_vec()).unwrap();

        // 수신: 세션 수락 + 첫 평문 꺼내기
        let b_sess = bob.accept_session(alice.public_key(), wire).unwrap();
        assert_eq!(b_sess.take_first_message().as_deref(), Some("작전 개시".as_bytes()));
        assert_eq!(b_sess.take_first_message(), None); // 두 번째는 비어 있음

        // 양방향
        let w = b_sess.encrypt("수신 양호".as_bytes().to_vec()).unwrap();
        assert_eq!(a_sess.decrypt(w).unwrap(), "수신 양호".as_bytes());
    }

    #[test]
    fn ffi_안전번호_일치() {
        let a = Identity::generate();
        let b = Identity::generate();
        assert_eq!(
            a.safety_number_with(b.public_key()).unwrap(),
            b.safety_number_with(a.public_key()).unwrap()
        );
    }

    #[test]
    fn ffi_잘못된_키길이는_오류() {
        let id = Identity::generate();
        assert!(matches!(id.save(vec![0u8; 16]), Err(CoreError::BadKeyLength)));
    }

    #[test]
    fn ffi_신원_저장_복원() {
        let id = Identity::generate();
        let saved = id.save(KEY.to_vec()).unwrap();
        let restored = Identity::restore(saved, KEY.to_vec()).unwrap();
        assert_eq!(id.public_key(), restored.public_key());
    }
}
