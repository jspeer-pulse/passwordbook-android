# PasswordBook — Project Overview

## What

An Android password manager for storing platform credentials (websites, apps, etc.).
UI is Chinese; code identifiers and "PasswordBook" are English (GitHub/SEO friendly).

## Tech Stack

- **Platform**: Android (Java 11), minSdk 24, targetSdk 36
- **Database**: SQLite (`passwords.db`, VERSION=2)
- **Crypto**: PBKDF2WithHmacSHA256 (200,000 iterations) + AES-256-GCM (128-bit auth tag)
- **Transfer**: ECDH (P-256) + 4-emoji verification code + AES-GCM session encryption
- **QR**: ZXing core 3.5.3 + zxing-android-embedded 4.3.0
- **Build**: Gradle KTS

## Security Architecture (v2.0.0)

```
master_password + db_salt(16B) → PBKDF2(200K) → master_key (AES-256)
                                                       │
                                    derived once at unlock, cached in MyApp
                                    (background thread, no UI block)
                                                       │
                                    ┌──────────────────┼──────────────────┐
                                    ▼                  ▼                  ▼
                              verification token   DB entry 1         DB entry N
                              AES-256-GCM (V2)    AES-256-GCM (V2)   AES-256-GCM (V2)
                              + random IV(12B)    + random IV(12B)   + random IV(12B)
```

- **Key derivation**: iterations stored in SharedPreferences at setup time, NOT from code constant. Changing `CryptoHelper.ITERATIONS` never breaks old data.
- **Data encryption**: per-entry random IV, identical plaintexts → different ciphertexts
- **Integrity**: GCM 128-bit auth tag, tampered ciphertext → `AEADBadTagException`
- **Formats**: V2 (key-based, `PBK\x02` + IV + ciphertext), V1 (`PBK\x01` + iterations + salt + IV + ciphertext), Legacy (salt + IV + ciphertext, 10K iters). V1/Legacy auto-migrate to V2 on read.

## Project Structure

```
app/src/main/java/com/example/passwordbook/
├── MainActivity.java              # Main screen: list + add/delete + export/import/transfer buttons
├── LockActivity.java              # Lock screen: setup/verify/change master password
├── ChangePasswordActivity.java    # Standalone change-password page (background re-encrypt)
├── TransferActivity.java          # LAN transfer: send/receive with ECDH + emoji verify
├── MyApp.java                     # Application: caches master_key (SecretKey) + legacyPassword (char[])
├── PasswordDatabaseHelper.java    # SQLite CRUD (V2 key-based), RawEntry for export, changeMasterPassword
├── PasswordItem.java              # POJO model
├── PasswordAdapter.java           # RecyclerView adapter (delete callback only)
├── PasswordStore.java             # SharedPreferences: db_salt + iterations + verification token
├── CryptoHelper.java              # PBKDF2, AES-GCM, V1/V2 encrypt/decrypt, legacy compat
├── TransferCrypto.java            # ECDH key exchange, session key, emoji verification code
└── ExportImportHelper.java        # JSON export/import (encrypted entries, salt, token)

app/src/main/res/layout/
├── activity_main.xml              # Title + Export/Import/Transfer buttons + input form + RecyclerView
├── activity_lock.xml              # PasswordBook title + hint + password field + action button
├── activity_change_password.xml   # Old password + new password + confirm fields
├── activity_transfer.xml          # Mode selection → send (QR+pairing code+emoji+confirm+cancel)
│                                  #               → receive (scan QR, IP/port/code, emoji, import+cancel)
├── item_password.xml              # List item: platform + account + password + Del button
├── dialog_delete.xml              # Delete confirmation with platform/account/password preview
└── dialog_change_password.xml     # Old password + new password + confirm fields
```

## Features

1. **Master password**: first-launch setup (≥6 chars), required on each cold start
2. **Full encryption**: all account/password fields AES-256-GCM encrypted, key cached for speed
3. **Password management**: add / delete entries, RecyclerView list
4. **Change master password**: re-encrypts ALL entries with new key (background thread)
5. **Auto-migration**: V1/Legacy encrypted data auto-upgraded to V2 on read
6. **Export/Import**: JSON file (encrypted), SAF file picker, password-verified on import
7. **LAN Transfer**: ECDH + 6-digit pairing code + 4-emoji verification + AES-GCM encrypted data
8. **QR code**: Sender generates QR (IP|port|pairing_code), receiver scans to auto-fill

## Transfer Protocol (MITM-resistant)

```
Sender generates pairing_code + QR(ip|port|code)
Receiver scans QR or enters IP + port + code
    ↓
ECDH handshake (P-256 ephemeral keys)
    ↓
session_key = SHA-256(ECDH_secret || pairing_code || "session")
emoji_code  = SHA-256(ECDH_secret || pairing_code || "emoji") → 4 emojis (24^4 combinations)
    ↓
Both show emoji + info text + [Confirm] + [Stop Transfer]
Both must press Confirm → receiver sends "ready" → sender sends encrypted export JSON
    ↓
Receiver imports: derives key with password + imported salt → verifies token → replaces DB
```

**MITM resistance**: attacker without pairing_code gets wrong session_key → can't decrypt. Attacker with pairing_code doing MitM relay produces different ECDH secret → different emoji → user detects mismatch and clicks "Stop Transfer". Both sides return to main screen on cancel.

## Iteration Count

**200,000** PBKDF2-HMAC-SHA256 — stored in SharedPreferences at setup, read back on unlock.
Changing `CryptoHelper.ITERATIONS` in code never breaks existing data.

## Key Bugs Fixed (v2.0.0 development)

- `setMasterPassword` zeroing same-reference char[] → added `masterPassword != password` guard
- `changeMasterPassword` nested DB open/close breaking transaction → inlined read into single connection
- `ECGenParameterSpec` reflection → standard constructor
- `MaterialAlertDialogBuilder` auto-dismiss on validation failure → `setPositiveButton(null)` + `getButton().setOnClickListener()`
- `android:passwordToggleEnabled` → `app:passwordToggleEnabled`
- `MaterialButton.setBackgroundTint()` → `setBackgroundTintList(ColorStateList.valueOf())`

## Version History

| Version | Changes |
|---------|---------|
| v1.0.0 | Basic password manager |
| v1.1.0 | Delete confirmation dialog |
| v1.2.0 | Master password protection |
| v2.0.0 | Key-hierarchy crypto + auto-migration + export/import + LAN transfer with ECDH + Chinese UI |
