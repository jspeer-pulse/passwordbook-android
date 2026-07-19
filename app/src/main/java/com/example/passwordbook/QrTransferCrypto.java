package com.example.passwordbook;

import android.graphics.Bitmap;
import android.util.Base64;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * QR code transfer cryptography — v5 pure ECDH bidirectional key exchange.
 *
 * <h3>Elliptic curve: secp256r1 (NIST P-256)</h3>
 * <pre>
 *   Curve equation: y² ≡ x³ + ax + b (mod p)
 *
 *   p = 0x FFFFFFFF 00000001 00000000 00000000
 *               00000000 FFFFFFFF FFFFFFFF FFFFFFFF
 *     = 2²⁵⁶ − 2²²⁴ + 2¹⁹² + 2⁹⁶ − 1
 *
 *   a = 0x FFFFFFFF 00000001 00000000 00000000
 *               00000000 FFFFFFFF FFFFFFFF FFFFFFFC
 *     = −3 (mod p)
 *
 *   b = 0x 5AC635D8 AA3A93E7 B3EBBD55 769886BC
 *               651D06B0 CC53B0F6 3BCE3C3E 27D2604B
 *
 *   G = (Gx, Gy)
 *   Gx = 0x 6B17D1F2 E12C4247 F8BCE6E5 63A440F2
 *                77037D81 2DEB33A0 F4A13945 D898C296
 *   Gy = 0x 4FE342E2 FE1A7F9B 8EE7EB4A 7C0F9E16
 *                2BCE3357 6B315ECE CBB64068 37BF51F5
 *
 *   n = 0x FFFFFFFF 00000000 FFFFFFFF FFFFFFFF
 *               BCE6FAAD A7179E84 F3B9CAC2 FC632551
 *     (order of G, i.e. n·G = O)
 *
 *   h = 1 (cofactor)
 * </pre>
 *
 * <h3>Protocol (v5)</h3>
 * <ol>
 *   <li>Receiver generates keypair (db, Qb = db·G), opens camera.</li>
 *   <li>Sender generates keypair (da, Qa = da·G), shows Qa as QR.</li>
 *   <li>Receiver scans Qa QR, then shows Qb as QR.</li>
 *   <li>Sender scans Qb QR.</li>
 *   <li>Both compute shared secret: S = da·Qb = db·Qa = da·db·G.</li>
 *   <li>Encryption key: K = SHA-256("PasswordBook-ECDH-v5" || S).</li>
 *   <li>Sender: AES-256-GCM(K, exportJson) → per-frame QR codes.</li>
 *   <li>Receiver: scans frames, AES-256-GCM(K, …) → decrypts → imports.</li>
 * </ol>
 *
 * <p>Neither private key (da, db) ever leaves its device.
 * Eavesdropper sees Qa and Qb but cannot compute da·db·G (ECDLP).
 * Key K is ephemeral — generated fresh each transfer and zeroed after use.</p>
 *
 * <h3>QR string format (v5)</h3>
 * <pre>
 *   Pubkey QR:  PBPK|&lt;base64 X.509 encoded public key&gt;
 *   Data frame:  PB5|&lt;index&gt;|&lt;total&gt;|&lt;iv_b64&gt;|&lt;ct_b64&gt;
 * </pre>
 */
public class QrTransferCrypto {

    // ── Constants ────────────────────────────────────
    private static final String MAGIC_PUBKEY = "PBPK";
    private static final String MAGIC_DATA   = "PB5";
    private static final String SEP = "|";
    private static final String EC_ALGO   = "EC";
    private static final String EC_CURVE  = "secp256r1";   // NIST P-256
    private static final String ECDH_ALGO = "ECDH";
    private static final int AES_KEY_LEN  = 32;            // AES-256
    private static final int GCM_IV_LEN   = 12;
    private static final int GCM_TAG_LEN  = 128;

    // QR Version 40 Byte-mode capacity ~2953 bytes.
    // Header ~50 bytes (magic + index + total + iv). Base64 overhead ~33%.
    // Available plaintext per frame: ~2050 bytes.
    public static final int MAX_CHUNK_SIZE = 1800;

    // ── ECDH ─────────────────────────────────────────

    /** Generate an ephemeral secp256r1 (P-256) key pair. */
    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance(EC_ALGO);
        gen.initialize(new ECGenParameterSpec(EC_CURVE));
        return gen.generateKeyPair();
    }

    /** Encode a public key to Base64 for QR transmission. */
    public static String encodePublicKey(PublicKey key) {
        return Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP);
    }

    /** Decode a public key from a scanned QR string. */
    public static PublicKey decodePublicKey(String base64) throws Exception {
        byte[] encoded = Base64.decode(base64, Base64.NO_WRAP);
        KeyFactory factory = KeyFactory.getInstance(EC_ALGO);
        return factory.generatePublic(new X509EncodedKeySpec(encoded));
    }

    /**
     * Compute the ECDH shared secret: ourPrivate · peerPublic.
     * Returns the raw 32-byte x-coordinate of the shared point (for P-256).
     */
    public static byte[] computeSharedSecret(KeyPair ourKeyPair, PublicKey peerPublicKey)
            throws Exception {
        KeyAgreement ka = KeyAgreement.getInstance(ECDH_ALGO);
        ka.init(ourKeyPair.getPrivate());
        ka.doPhase(peerPublicKey, true);
        return ka.generateSecret();
    }

    /**
     * Derive the AES-256 encryption key from the ECDH shared secret.
     * K = SHA-256(domain_separator || shared_secret)
     */
    public static SecretKey deriveEncryptionKey(byte[] sharedSecret) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update("PasswordBook-ECDH-v5".getBytes(StandardCharsets.UTF_8));
        md.update(sharedSecret);
        byte[] keyBytes = md.digest();
        SecretKey key = new SecretKeySpec(keyBytes, "AES");
        Arrays.fill(keyBytes, (byte) 0);
        return key;
    }

    // ── Pubkey QR ────────────────────────────────────

    /** Format: PBPK|&lt;base64 pubkey&gt; */
    public static String makePubkeyQr(PublicKey pubKey) {
        return MAGIC_PUBKEY + SEP + encodePublicKey(pubKey);
    }

    /** Parse a pubkey QR scanned by the peer. */
    public static PublicKey parsePubkeyQr(String data) throws Exception {
        String[] parts = data.split("\\" + SEP);
        if (parts.length != 2 || !MAGIC_PUBKEY.equals(parts[0])) {
            throw new IllegalArgumentException("非法的公钥二维码");
        }
        return decodePublicKey(parts[1]);
    }

    /** Check if a scanned string is a pubkey QR (for disambiguation). */
    public static boolean isPubkeyQr(String data) {
        return data != null && data.startsWith(MAGIC_PUBKEY + SEP);
    }

    /** Check if a scanned string is a data frame (for disambiguation). */
    public static boolean isDataFrame(String data) {
        return data != null && data.startsWith(MAGIC_DATA + SEP);
    }

    // ── Data frame ───────────────────────────────────

    /** A single encrypted data frame. */
    public static class Frame {
        public int index;
        public int total;
        public byte[] iv;         // 12 bytes, random per frame
        public byte[] ciphertext; // AES-256-GCM encrypted chunk

        /** Format: PB5|index|total|iv_b64|ct_b64 */
        public String toQrString() {
            String ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP);
            String ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            return MAGIC_DATA + SEP + index + SEP + total + SEP + ivB64 + SEP + ctB64;
        }

        /** Parse a v5 data frame from a scanned QR string. */
        public static Frame fromQrString(String data) throws Exception {
            String[] parts = data.split("\\" + SEP);
            if (parts.length != 5 || !MAGIC_DATA.equals(parts[0])) {
                throw new IllegalArgumentException("非法的传输帧格式");
            }
            Frame f = new Frame();
            f.index      = Integer.parseInt(parts[1]);
            f.total      = Integer.parseInt(parts[2]);
            f.iv         = Base64.decode(parts[3], Base64.NO_WRAP);
            f.ciphertext = Base64.decode(parts[4], Base64.NO_WRAP);

            if (f.index < 0 || f.total <= 0 || f.index >= f.total) {
                throw new IllegalArgumentException("帧序号无效: " + f.index + "/" + f.total);
            }
            if (f.iv.length != GCM_IV_LEN) {
                throw new IllegalArgumentException("IV 长度无效");
            }
            return f;
        }

        /** Decrypt this frame's chunk with the given AES key. */
        public String decryptChunk(SecretKey encryptionKey) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey,
                    new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, StandardCharsets.UTF_8);
        }
    }

    // ── Frame creation (sender side) ─────────────────

    /**
     * Encrypt export JSON into per-frame AES-256-GCM QR codes.
     *
     * @param exportJson    the exported data (already master-password-encrypted at rest)
     * @param encryptionKey K = SHA-256(domain || ECDH_shared_secret)
     * @return list of QR frames to display
     */
    public static List<Frame> createFrames(String exportJson, SecretKey encryptionKey)
            throws Exception {
        byte[] jsonBytes = exportJson.getBytes(StandardCharsets.UTF_8);
        int total = (int) Math.ceil((double) jsonBytes.length / MAX_CHUNK_SIZE);
        if (total < 1) total = 1;

        List<Frame> frames = new ArrayList<>(total);

        for (int i = 0; i < total; i++) {
            int start = i * MAX_CHUNK_SIZE;
            int end = Math.min(start + MAX_CHUNK_SIZE, jsonBytes.length);
            byte[] chunk = Arrays.copyOfRange(jsonBytes, start, end);

            byte[] iv = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey,
                    new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] ciphertext = cipher.doFinal(chunk);

            Frame f = new Frame();
            f.index = i;
            f.total = total;
            f.iv = iv;
            f.ciphertext = ciphertext;
            frames.add(f);
        }

        return frames;
    }

    // ── Assembly (receiver side) ─────────────────────

    /**
     * Decrypt and assemble received frames into the original export JSON.
     *
     * @param frames        received frames, ordered by index
     * @param encryptionKey K = SHA-256(domain || ECDH_shared_secret)
     */
    public static String assembleFrames(Frame[] frames, SecretKey encryptionKey)
            throws Exception {
        if (frames == null || frames.length == 0) {
            throw new IllegalArgumentException("没有接收到任何帧");
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < frames.length; i++) {
            if (frames[i] == null) {
                throw new IllegalArgumentException("缺少第 " + (i + 1) + " 帧");
            }
            sb.append(frames[i].decryptChunk(encryptionKey));
        }

        return sb.toString();
    }

    // ── QR bitmap ────────────────────────────────────

    /** Generate a QR code bitmap from a string. */
    public static Bitmap generateQrBitmap(String data, int size) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
        }
        return bitmap;
    }

    // ── Zeroing ──────────────────────────────────────

    /** Zero all sensitive data in an array of frames. */
    public static void zeroAllFrames(Frame[] frames) {
        if (frames != null) {
            for (Frame f : frames) {
                zeroFrame(f);
            }
        }
    }

    /** Zero sensitive fields in a single frame. */
    public static void zeroFrame(Frame f) {
        if (f != null) {
            if (f.iv != null)         Arrays.fill(f.iv, (byte) 0);
            if (f.ciphertext != null) Arrays.fill(f.ciphertext, (byte) 0);
        }
    }

    /** Destroy a KeyPair's private key material. */
    public static void zeroKeyPair(KeyPair kp) {
        if (kp != null) {
            try { kp.getPrivate().destroy(); } catch (Exception ignored) {}
        }
    }

    /** Destroy a SecretKey. */
    public static void zeroKey(SecretKey key) {
        if (key != null) {
            try { key.destroy(); } catch (Exception ignored) {}
        }
    }
}
