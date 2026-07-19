// CryptoHelper.java
package com.example.passwordbook;

import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 密码学工具类。
 *
 * 密钥分层：
 *   master_password + salt → PBKDF2(600K) → master_key（解锁时派生一次，缓存于 MyApp）
 *   master_key + 随机IV → AES-256-GCM → 每条数据库记录 / 验证令牌
 *
 * 密文格式：
 *   V2（密钥加密，用于数据库记录和验证令牌）：
 *       MAGIC_V2(4) + IV(12) + ciphertext — 无 salt/iterations，密钥已预派生
 *   V1（密码加密，仅用于兼容旧验证令牌）：
 *       MAGIC_V1(4) + iterations(4) + salt(16) + IV(12) + ciphertext
 *   Legacy（v1.2.0 旧格式，10K 迭代）：
 *       salt(16) + IV(12) + ciphertext
 */
public class CryptoHelper {
    private static final int IV_LENGTH = 12;          // GCM 推荐 12 字节
    private static final int TAG_LENGTH = 128;        // GCM 认证标签
    private static final int SALT_LENGTH = 16;        // PBKDF2 盐值
    private static final int KEY_LENGTH = 256;        // AES-256

    // OWASP 2023: PBKDF2-HMAC-SHA256 ≥ 600,000 for server;
    // 200,000 for mobile balances security (~0.5–1s on mid-range) with UX
    public static final int ITERATIONS = 200_000;
    private static final int LEGACY_ITERATIONS = 10_000;

    // 格式魔数（"PBK" + 版本号）
    private static final byte[] MAGIC_V1 = {0x50, 0x42, 0x4B, 0x01}; // 密码加密（含 salt+iterations）
    private static final byte[] MAGIC_V2 = {0x50, 0x42, 0x4B, 0x02}; // 密钥加密（无 salt）

    private static final String PBKDF2_ALGO = "PBKDF2WithHmacSHA256";
    private static final String AES_ALGO = "AES/GCM/NoPadding";

    // ──────────────────────────────────────────────
    // 密钥派生
    // ──────────────────────────────────────────────
    public static SecretKey deriveKey(char[] password, byte[] salt, int iterations) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGO);
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
        SecretKey tmp = factory.generateSecret(spec);
        byte[] encoded = tmp.getEncoded();
        SecretKey key = new SecretKeySpec(encoded, "AES");
        // 清除中间密钥材料
        Arrays.fill(encoded, (byte) 0);
        return key;
    }

    // ──────────────────────────────────────────────
    // V2：密钥加密（快速路径 — 无 PBKDF2）
    // 格式：MAGIC_V2(4) + IV(12) + ciphertext
    // ──────────────────────────────────────────────
    public static String encryptWithKey(SecretKey key, String plaintext) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        ByteBuffer buf = ByteBuffer.allocate(MAGIC_V2.length + iv.length + ciphertext.length);
        buf.put(MAGIC_V2);
        buf.put(iv);
        buf.put(ciphertext);
        return Base64.encodeToString(buf.array(), Base64.DEFAULT);
    }

    public static String decryptWithKey(SecretKey key, String encryptedBase64) throws Exception {
        byte[] combined = Base64.decode(encryptedBase64, Base64.DEFAULT);
        ByteBuffer buf = ByteBuffer.wrap(combined);

        // 读取魔数
        byte[] magic = new byte[4];
        buf.get(magic);
        if (!Arrays.equals(magic, MAGIC_V2)) {
            throw new IllegalArgumentException("Not a V2 key-encrypted payload");
        }

        byte[] iv = new byte[IV_LENGTH];
        buf.get(iv);
        byte[] ciphertext = new byte[buf.remaining()];
        buf.get(ciphertext);

        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
        byte[] plainBytes = cipher.doFinal(ciphertext);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    // ──────────────────────────────────────────────
    // V1：密码加密（兼容路径 — 含 salt + iterations）
    // 格式：MAGIC_V1(4) + iterations(4) + salt(16) + IV(12) + ciphertext
    // ──────────────────────────────────────────────
    public static String encrypt(char[] password, String plaintext) throws Exception {
        byte[] salt = new byte[SALT_LENGTH];
        byte[] iv = new byte[IV_LENGTH];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        random.nextBytes(iv);

        SecretKey key = deriveKey(password, salt, ITERATIONS);
        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        ByteBuffer buf = ByteBuffer.allocate(
                MAGIC_V1.length + 4 + salt.length + iv.length + ciphertext.length);
        buf.put(MAGIC_V1);
        buf.putInt(ITERATIONS);
        buf.put(salt);
        buf.put(iv);
        buf.put(ciphertext);
        return Base64.encodeToString(buf.array(), Base64.DEFAULT);
    }

    /**
     * 解密 V1 或 Legacy 格式的密文。
     * 不支持 V2 格式（V2 请用 {@link #decryptWithKey}）。
     */
    public static String decrypt(char[] password, String encryptedBase64) throws Exception {
        byte[] combined = Base64.decode(encryptedBase64, Base64.DEFAULT);

        int iterations;
        byte[] salt;
        byte[] iv;
        byte[] ciphertext;

        if (combined.length >= 4 && matchesMagic(combined, MAGIC_V1)) {
            // V1 格式
            ByteBuffer buf = ByteBuffer.wrap(combined);
            buf.getInt(); // 跳过魔数
            iterations = buf.getInt();
            salt = new byte[SALT_LENGTH];
            iv = new byte[IV_LENGTH];
            buf.get(salt);
            buf.get(iv);
            ciphertext = new byte[buf.remaining()];
            buf.get(ciphertext);
        } else {
            // Legacy 格式（v1.2.0，10K 迭代）
            iterations = LEGACY_ITERATIONS;
            salt = new byte[SALT_LENGTH];
            iv = new byte[IV_LENGTH];
            ciphertext = new byte[combined.length - salt.length - iv.length];
            System.arraycopy(combined, 0, salt, 0, salt.length);
            System.arraycopy(combined, salt.length, iv, 0, iv.length);
            System.arraycopy(combined, salt.length + iv.length, ciphertext, 0, ciphertext.length);
        }

        SecretKey key = deriveKey(password, salt, iterations);
        Cipher cipher = Cipher.getInstance(AES_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
        byte[] plainBytes = cipher.doFinal(ciphertext);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────
    public static void zeroCharArray(char[] chars) {
        if (chars != null) {
            Arrays.fill(chars, '\0');
        }
    }

    public static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        new SecureRandom().nextBytes(salt);
        return salt;
    }

    private static boolean matchesMagic(byte[] data, byte[] magic) {
        return data[0] == magic[0] && data[1] == magic[1]
                && data[2] == magic[2] && data[3] == magic[3];
    }
}
