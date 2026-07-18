// PasswordStore.java
package com.example.passwordbook;

import android.content.Context;
import android.content.SharedPreferences;

public class PasswordStore {
    private static final String PREFS_NAME = "password_prefs";
    private static final String KEY_ENCRYPTED = "encrypted_valid";
    private static final String KEY_SALT = "salt"; // 其实 salt 包含在密文中，我们不需要单独存储，但为了通用，可以省略

    public static boolean isFirstTime(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return !prefs.contains(KEY_ENCRYPTED);
    }

    public static void saveEncryptedValid(Context context, char[] masterPassword) throws Exception {
        String encrypted = CryptoHelper.encrypt(masterPassword, "Valid");
        SharedPreferences.Editor editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_ENCRYPTED, encrypted);
        editor.apply();
    }

    public static boolean verifyPassword(Context context, char[] inputPassword) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String encrypted = prefs.getString(KEY_ENCRYPTED, null);
        if (encrypted == null) return false;
        try {
            String decrypted = CryptoHelper.decrypt(inputPassword, encrypted);
            return "Valid".equals(decrypted);
        } catch (Exception e) {
            return false; // 解密失败，密码错误
        }
    }
}