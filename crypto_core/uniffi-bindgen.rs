// UniFFI 바인딩 생성기 진입점.
// 사용: cargo run --bin uniffi-bindgen -- generate --library <cdylib> --language swift|kotlin --out-dir <dir>
fn main() {
    uniffi::uniffi_bindgen_main()
}
