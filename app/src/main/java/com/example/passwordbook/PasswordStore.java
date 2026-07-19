// PasswordStore.java
package com.example.passwordbook;

import android.content.Context;
import android.content.SharedPreferences;

import javax.crypto.SecretKey;

/**
 * 管理主密码验证令牌和数据库盐值的 SharedPreferences 存储。
 *
 * 存储内容：
 *   - db_salt: 16 字节随机盐（Base64），用于从主密码派生 master_key
 *   - encrypted_valid: V2 格式加密的 "Valid" 字符串，用 master_key 加解密
 */
public class PasswordStore {
    private static final String PREFS_NAME = "password_prefs";
    private static final String KEY_DB_SALT = "db_salt";
    private static final String KEY_ENCRYPTED_VALID = "encrypted_valid";
    private static final String KEY_ITERATIONS = "iterations";
    private static final String KEY_ENCRYPTED_VALID_BACKUP = "encrypted_valid_backup";

    public static boolean isFirstTime(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return !prefs.contains(KEY_ENCRYPTED_VALID);
    }

    // ──────────────────────────────────────────────
    // 首次设置主密码
    // ──────────────────────────────────────────────
    /**
     * 生成盐值，派生密钥，加密验证令牌。返回派生好的 SecretKey 供 MyApp 缓存。
     */
    public static SecretKey setupMasterPassword(Context context, char[] password) throws Exception {
        byte[] dbSalt = CryptoHelper.generateSalt();
        int iterations = CryptoHelper.ITERATIONS;

        SecretKey key = CryptoHelper.deriveKey(password, dbSalt, iterations);
        String token = CryptoHelper.encryptWithKey(key, "Valid");

        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_DB_SALT, android.util.Base64.encodeToString(dbSalt, android.util.Base64.DEFAULT));
        editor.putInt(KEY_ITERATIONS, iterations);
        editor.putString(KEY_ENCRYPTED_VALID, token);
        editor.commit();
        
        return key;
    }

    // ──────────────────────────────────────────────
    // 验证密码并派生密钥
    // ──────────────────────────────────────────────
    /**
     * 用输入的密码尝试派生密钥并解密验证令牌。
     * 成功返回 SecretKey（调用方应缓存到 MyApp），失败返回 null。
     *
     * 同时兼容旧格式令牌（V1 或 Legacy），验证成功后自动迁移到 V2 格式。
     */
    public static SecretKey verifyAndDeriveKey(Context context, char[] password) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_ENCRYPTED_VALID, null);
        String backupToken = prefs.getString(KEY_ENCRYPTED_VALID_BACKUP, null);
        if (token == null && backupToken == null) return null;

        String dbSaltB64 = prefs.getString(KEY_DB_SALT, null);

        if (dbSaltB64 != null) {
            try {
                byte[] dbSalt = android.util.Base64.decode(dbSaltB64, android.util.Base64.DEFAULT);
                int iterations = prefs.getInt(KEY_ITERATIONS, CryptoHelper.ITERATIONS);
                SecretKey key = CryptoHelper.deriveKey(password, dbSalt, iterations);

                // Try primary token first
                if (token != null) {
                    try {
                        String decrypted = CryptoHelper.decryptWithKey(key, token);
                        if ("Valid".equals(decrypted)) {
                            // Primary token matched. Clear any stale backup —
                            // if DB was already migrated, backup is the OLD token
                            // and must not be promoted if user later enters old password.
                            if (backupToken != null) {
                                prefs.edit().remove(KEY_ENCRYPTED_VALID_BACKUP).commit();
                            }
                            return key;
                        }
                    } catch (Exception ignored) { }
                }

                // Primary failed — try backup token (crash recovery).
                // Backup matches only if crash happened before DB migration
                // (token was written but DB still old, or backup saved but
                // new token never written). Promote backup to primary.
                if (backupToken != null) {
                    try {
                        String decrypted = CryptoHelper.decryptWithKey(key, backupToken);
                        if ("Valid".equals(decrypted)) {
                            prefs.edit()
                                    .putString(KEY_ENCRYPTED_VALID, backupToken)
                                    .remove(KEY_ENCRYPTED_VALID_BACKUP)
                                    .commit();
                            return key;
                        }
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            return null;
        }

        // 旧格式兼容：没有 db_salt，用 V1/Legacy 解密
        if (token != null) {
            try {
                String decrypted = CryptoHelper.decrypt(password, token);
                if ("Valid".equals(decrypted)) {
                    // 迁移到新格式
                    byte[] newSalt = CryptoHelper.generateSalt();
                    int iterations = CryptoHelper.ITERATIONS;
                    SecretKey newKey = CryptoHelper.deriveKey(password, newSalt, iterations);
                    String newToken = CryptoHelper.encryptWithKey(newKey, "Valid");
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putString(KEY_DB_SALT,
                            android.util.Base64.encodeToString(newSalt, android.util.Base64.DEFAULT));
                    editor.putInt(KEY_ITERATIONS, iterations);
                    editor.putString(KEY_ENCRYPTED_VALID, newToken);
                    editor.remove(KEY_ENCRYPTED_VALID_BACKUP);
                    editor.commit();
                    return newKey;
                }
            } catch (Exception ignored) { }
        }

        return null;
    }

    // ──────────────────────────────────────────────
    // 修改密码后更新令牌
    // ──────────────────────────────────────────────
    /**
     * 用新密钥重新加密验证令牌。用于修改主密码后。
     * @return true if the token was successfully written to disk
     */
    public static boolean updateForNewKey(Context context, SecretKey newKey) throws Exception {
        String token = CryptoHelper.encryptWithKey(newKey, "Valid");
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_ENCRYPTED_VALID, token);
        return editor.commit();
    }

    /**
     * Save the current token as a backup before starting a password change.
     * If the app crashes mid-change, this backup allows recovery.
     */
    public static boolean backupCurrentToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_ENCRYPTED_VALID, null);
        if (token == null) return false;
        return prefs.edit().putString(KEY_ENCRYPTED_VALID_BACKUP, token).commit();
    }

    /** Clear the backup token after a successful password change. */
    public static void clearBackupToken(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().remove(KEY_ENCRYPTED_VALID_BACKUP).commit();
    }

    /**
     * Restore the backup token (called when DB migration fails).
     * @return true if restored successfully
     */
    public static boolean restoreBackupToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String backup = prefs.getString(KEY_ENCRYPTED_VALID_BACKUP, null);
        if (backup == null) return false;
        boolean ok = prefs.edit()
                .putString(KEY_ENCRYPTED_VALID, backup)
                .remove(KEY_ENCRYPTED_VALID_BACKUP)
                .commit();
        return ok;
    }

    /**
     * Check if there's an unfinished password change (crash recovery).
     * Call this on app startup. Does NOT auto-restore — the backup token
     * is kept so verifyAndDeriveKey can fall back to it if needed.
     * @return true if an interrupted change was detected
     */
    public static boolean detectInterruptedChange(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.contains(KEY_ENCRYPTED_VALID_BACKUP);
    }

    /**
     * Get the backup token (if any). Used as fallback during password verification.
     */
    static String getBackupToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_ENCRYPTED_VALID_BACKUP, null);
    }

    // 保留旧接口兼容
    @Deprecated
    public static void saveEncryptedValid(Context context, char[] masterPassword) throws Exception {
        setupMasterPassword(context, masterPassword);
    }

    @Deprecated
    public static boolean verifyPassword(Context context, char[] inputPassword) {
        return verifyAndDeriveKey(context, inputPassword) != null;
    }

    public static byte[] getDbSalt(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String b64 = prefs.getString(KEY_DB_SALT, null);
        if (b64 == null) return null;
        return android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
    }

    /**
     * Get the iteration count used when the master password was originally set up.
     * Returns CryptoHelper.ITERATIONS if not found (old data without stored count).
     */
    public static int getIterations(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_ITERATIONS, CryptoHelper.ITERATIONS);
    }
}
