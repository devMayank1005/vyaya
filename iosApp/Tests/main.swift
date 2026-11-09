import CommonCrypto
import Foundation

// Checks iosApp/iosApp/VyayaAesGcm.swift on the Mac (CryptoKit is the same there): NIST GCM vectors, tampering, and
// the spec's own vector (docs/encrypted-backup-v1.md) decrypted with a CommonCrypto PBKDF2 key. Run by
// scripts/test-ios-crypto.sh; exits non-zero on any failure.

func hex(_ s: String) -> Data {
    var d = Data(); var i = s.startIndex
    while i < s.endIndex { let j = s.index(i, offsetBy: 2); d.append(UInt8(s[i..<j], radix: 16)!); i = j }
    return d
}
func hexOf(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

setbuf(stdout, nil) // print as it goes, even if a check crashes
var failures = 0
func check(_ ok: Bool, _ what: String) { print((ok ? "ok   " : "FAIL ") + what); if !ok { failures += 1 } }

// NIST / McGrew-Viega AES-256 GCM test cases 13 and 14 (key 0^32, nonce 0^12, no AAD).
let zeroKey = Data(count: 32), zeroNonce = Data(count: 12)
let t13 = try! VyayaAesGcm.seal(key: zeroKey, nonce: zeroNonce, aad: Data(), plain: Data())
check(hexOf(t13) == "530f8afbc74536b9a963b4f1c4cb738b", "GCM test case 13 (empty)")
let t14 = try! VyayaAesGcm.seal(key: zeroKey, nonce: zeroNonce, aad: Data(), plain: Data(count: 16))
check(hexOf(t14) == "cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919", "GCM test case 14")
check(VyayaAesGcm.open(key: zeroKey, nonce: zeroNonce, aad: Data(), sealed: t14) == Data(count: 16), "open test case 14")
var changed = t14; changed[3] ^= 1
check(VyayaAesGcm.open(key: zeroKey, nonce: zeroNonce, aad: Data(), sealed: changed) == nil, "a changed byte is refused")
check(VyayaAesGcm.open(key: zeroKey, nonce: zeroNonce, aad: Data([1]), sealed: t14) == nil, "other associated data is refused")
check(VyayaAesGcm.open(key: zeroKey, nonce: zeroNonce, aad: Data(), sealed: Data(count: 5)) == nil, "too short is refused")

// The spec's "short" vector: password "correct horse battery staple", 1,000 iterations, salt 00..0f, chunk 16.
let file = hex("5659415941454e430101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a60000001088aa1e4322ad9d7d057c7135ccab005126213401c213aa215b6a9c961e")
let header = file.prefix(41)
let salt = file.subdata(in: 14..<30), prefix = file.subdata(in: 30..<37)
var key = Data(count: 32)
let password = Array("correct horse battery staple".utf8)
let status = key.withUnsafeMutableBytes { k in salt.withUnsafeBytes { s in
    CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), password.map { Int8(bitPattern: $0) }, password.count,
        s.bindMemory(to: UInt8.self).baseAddress, salt.count, CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), 1000,
        k.bindMemory(to: UInt8.self).baseAddress, 32)
} }
check(status == kCCSuccess, "PBKDF2")
let nonce = prefix + Data([0, 0, 0, 0, 1]) // chunk 0, final
let plain = VyayaAesGcm.open(key: key, nonce: nonce, aad: Data(header), sealed: Data(file.suffix(from: 41)))
check(plain.map { String(decoding: $0, as: UTF8.self) } == "hello, vyaya\n", "the spec's vector decrypts")

exit(failures == 0 ? 0 : 1)
