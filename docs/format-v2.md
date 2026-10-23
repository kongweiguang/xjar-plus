<!-- @author kongweiguang -->

# XJar Plus v2 binary format

This document is the Java/Go wire contract implemented by `xjar-plus-format`. All integers are big-endian. UTF-8 is
strict: malformed byte sequences and malformed Java surrogate pairs are rejected. There is no implicit Unicode
normalization.

## Cryptographic suite

- Content protection and key wrapping use only AES-256-GCM, with a 32-byte key, 12-byte nonce, 16-byte tag, and no
  padding. The encoded sealed value is `ciphertext || tag`.
- Password bytes are strict UTF-8 bytes of the supplied characters. Derivation is PBKDF2-HMAC-SHA256 with exactly
  600,000 iterations and a 16-byte salt, producing 32 bytes. This is the v2 engineering work factor, not a claimed
  NIST-mandated value; readers reject other values rather than accepting unbounded KDF work from an untrusted header.
  Password files preserve spaces and all characters except one final `LF`, `CRLF`, or `CR`; the file is limited to 64
  KiB.
- A new artifact gets a random 32-byte data key. The password-derived key wraps that data key; it is not used to encrypt
  each class or resource.
- `NonceSequence` gets a random 8-byte prefix per data-key lifetime, followed by a big-endian uint32 counter starting at
  zero. It fails after `0xffffffff`; it never wraps. The sequence is shared by index and entry blocks under that data
  key. Key-envelope and license nonces are independently random under their own keys.
- Java must reject an environment that cannot provide AES-256. No cipher or key-size fallback is permitted.

## Key envelope (`envelope.bin`)

The exact length is 100 bytes:

| Offset | Size | Meaning                                                          |
|-------:|-----:|------------------------------------------------------------------|
|      0 |    4 | ASCII `XKE2`                                                     |
|      4 |   16 | Random artifact ID bytes, exposed in APIs as canonical UUID text |
|     20 |   16 | Random PBKDF2 salt                                               |
|     36 |    4 | Iteration count, exactly `600000`                                |
|     40 |   12 | Random AES-GCM nonce                                             |
|     52 |   48 | Wrapped 32-byte data key plus 16-byte tag                        |

The GCM AAD is bytes `[0, 40)`, including magic, artifact ID, salt, and work factor. Parsing rejects unknown magic, any
extra or missing byte, or a work factor other than 600,000. A wrong password or a changed authenticated field fails GCM
authentication.

## Archive index (`index.bin`)

The outer record is `ASCII "XJX2" || nonce[12] || AES-GCM(indexPlaintext || tag)`. Its AAD is
`ASCII "XJX2" || artifactId[16]`.

The plaintext starts with `entryCount:uint32`. Entries are sorted by unsigned UTF-8 path bytes. Each entry is:

```text
pathLength:uint16 || path:utf8[pathLength] || flags:uint8 || originalLength:int64
|| crc32:uint32 || sha256[32]
```

Only flag bit 0 is defined (`1` means encrypted); all other flag values are rejected. CRC32 is unsigned. Paths are exact
slash-separated archive names, have 1..65535 UTF-8 bytes, and reject leading/trailing slash, backslash, NUL, duplicate
separators, `.` segments, and `..` segments. Nested archive paths use the literal `!/` separator, for example
`BOOT-INF/lib/a.jar!/p/A.class`. The count is at most 1,000,000 and the decrypted index at most 64 MiB. Index
ciphertext, tag, artifact ID, structure, duplicate paths, and trailing bytes are validated before an `ArchiveIndex` is
returned.

## Entry stream (`XJC2`)

Every encrypted entry is a self-describing stream. Header fields are authenticated as AAD for every block:

```text
header = ASCII "XJC2" || chunkSize:uint32(1048576) || totalLength:int64
block  = plaintextLength:uint32 || nonce[12] || ciphertext[plaintextLength] || tag[16]
```

The block AAD is:

```text
header[16] || artifactId[16] || pathLength:uint16 || path:utf8
|| chunkIndex:uint32 || plaintextLength:uint32
```

Blocks are exactly 1 MiB except for the final block. A zero-length entry still has one zero-length block and a GCM tag.
The total length supplied to the API must equal the header; each stored block length must equal its expected length. A
short stream, appended bytes, malformed header, wrong path or artifact, reordered block, or modified
ciphertext/nonce/length fails. Decryption buffers and authenticates one block before returning any bytes from that
block. The maximum number of blocks is `0xffffffff`.

## Issuer credentials (`.issuer`, `XJI2`)

```text
ASCII "XJI2" || appIdLength:uint32 || appId:utf8[appIdLength] || licenseKey[32]
```

`appId` must contain 1..256 UTF-8 bytes. The total file length must match exactly; trailing fields are invalid. This
file contains private issuance material and must not be included in a public release, logs, or ordinary configuration
exports.

## License (`key.x`, `XJL2`)

The record is `ASCII "XJL2" || nonce[12] || AES-GCM(licensePlaintext || tag)`. AAD is the exact UTF-8 string
`xjar-plus/license/v2`.

```text
licensePlaintext = appIdLength:uint32 || appId:utf8[appIdLength]
                 || notBefore:int64 || notAfter:int64
```

Times are UTC Unix epoch seconds. `notBefore` is nonnegative and inclusive. `notAfter == 0` means permanent; otherwise
it is exclusive and must be greater than `notBefore`. The encrypted record has no optional fields or trailing bytes. The
launcher must authenticate the complete record, compare the expected appId, and reject invalid intervals before starting
the application.

## Java API and failure contract

Package: `io.github.kongweiguang.xjar.plus.format` (Java 8, JDK-only).

| Type                                 | Contract                                                                                                                                                                                       |
|--------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Crypto`                             | `randomBytes(int)`, `seal`, `open`, `derive`; GCM/KDF failures are never downgraded or ignored. `derive` accepts only the v2 iteration count.                                                  |
| `KeyEnvelope`                        | `create(char[])`, `parse(byte[])`, `encode()`, `unwrap(char[])`, `getArtifactId()`, `getDataKey()`. Parsed envelopes have no plaintext key until unwrap.                                       |
| `NonceSequence`                      | `next()` returns a unique 12-byte nonce or fails on exhaustion.                                                                                                                                |
| `EntryMetadata` / `ArchiveIndex`     | Immutable values; digests and lists are defensive/immutable.                                                                                                                                   |
| `IndexCodec`                         | `seal` authenticates the whole index; `open` authenticates before parsing. Structural errors are `IllegalArgumentException`; GCM errors remain `GeneralSecurityException`.                     |
| `ChunkCipher`                        | `encrypt` checks source length and writes the framed stream; `decryptingStream` checks the header eagerly and raises `IOException` with the authentication exception as cause for a bad block. |
| `Passwords`                          | Strict bounded UTF-8 file reader; malformed UTF-8 and over-limit input are `IOException`.                                                                                                      |
| `IssuerCredentials` / `LicenseCodec` | Strict XJI2/XJL2 codecs; license opening authenticates and checks the expected appId before returning `LicenseGrant`.                                                                          |

`src/test/resources/vectors-v2.txt` contains public known-answer vectors shared with Go tests. Its keys are test
fixtures only and must never be used as production credentials.
