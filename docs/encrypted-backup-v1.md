# Encrypted backup file, version 1 (`.vyaya`)

A password-protected wrapper around the ordinary backup file (backup v3, one JSON object per line; its own
`schemaVersion` is unchanged). Written and read the same way by the Android and the iOS app. Plan.md Part D:
optional password encryption, AES-GCM with platform crypto, which also proves the file wasn't changed.

## Layout
All integers are big-endian.

| Bytes | Field | Value |
|---|---|---|
| 8 | magic | ASCII `VYAYAENC` |
| 1 | version | `0x01` |
| 1 | kdf | `0x01` = PBKDF2-HMAC-SHA256 |
| 4 | iterations | 600,000 when written; a reader accepts 1,000 to 10,000,000 |
| 16 | salt | random |
| 7 | nonce prefix | random |
| 4 | chunk size | 65,536 when written; a reader accepts 16 to 1,048,576 |

The 41 bytes above are the **header**. Then the chunks follow, each `AES-256-GCM(key, nonce, plaintext, aad = header)`,
stored as ciphertext followed by the 16-byte tag:

- `key = PBKDF2-HMAC-SHA256(UTF-8(password), salt, iterations, 32 bytes)`
  (a lone UTF-16 surrogate in the password is encoded as U+FFFD, bytes `EF BF BD`, on every platform)
- `nonce = prefix (7) || chunk index (4, from 0) || final (1: 0x01 for the last chunk, else 0x00)`
- Every chunk but the last holds exactly *chunk size* bytes of plaintext. The last holds fewer, possibly none:
  when the plaintext is an exact multiple of the chunk size, an empty last chunk is written.

## Reading
- Any tag that doesn't verify refuses the whole file, and nothing is restored (the restore runs in one transaction).
  A failure on the first chunk is reported as "wrong password, or the file is damaged".
- A full-size chunk is never the last one. The file ending after it means it was cut off.
- A shorter chunk must be the last: it is decrypted with `final = 0x01`, and nothing may follow it.
- Index and final flag in the nonce stop chunks being reordered, dropped, repeated, or the file being cut at a
  chunk boundary. The header as AAD stops any header field being changed.

## Test vectors
Password `correct horse battery staple`, iterations 1,000, salt `000102…0f`, prefix `a0a1a2a3a4a5a6`, chunk size 16.
They were made by an independent implementation (Python `cryptography`).

| Plaintext | File (hex) |
|---|---|
| `hello, vyaya\n` (13 bytes) | `5659415941454e430101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a60000001088aa1e4322ad9d7d057c7135ccab005126213401c213aa215b6a9c961e` |
| bytes `00`..`27` (40) | `5659415941454e430101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a60000001078eb5069957089abb257e78470ff249f50385353b604595e70f9034426aee8bb3ea27c4e3e0ebd9e2844093b05b8bae0d7c5e1c974f2e6efd03b5e7f1088d176099dfba1b8ec2e384895e0856307c03c4043af29efd6a717` |
| bytes `00`..`1f` (32) | `5659415941454e430101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a60000001078eb5069957089abb257e78470ff249f50385353b604595e70f9034426aee8bb3ea27c4e3e0ebd9e2844093b05b8bae0d7c5e1c974f2e6efd03b5e7f1088d17694c600a8d35873e07c0c2913e5b6db34` |
| empty | `5659415941454e430101000003e8000102030405060708090a0b0c0d0e0fa0a1a2a3a4a5a6000000104bba3f9c78507cafe5296892e2181491` |
