package com.example.passwordbook;

import android.app.Application;

import javax.crypto.SecretKey;
import java.util.Arrays;

/**
 * Application 级别的密钥缓存。
 * 解锁后派生一次密钥，进程存活期间复用，所有加解密操作均为毫秒级。
 */
public class MyApp extends Application {
    private char[] masterPassword;   // 用于兼容旧格式解密
    private SecretKey masterKey;     // 用于所有新格式加解密（V2）

    // ── masterPassword（兼容旧数据） ──
    public char[] getMasterPassword() {
        return masterPassword;
    }

    public void setMasterPassword(char[] password) {
        if (masterPassword != null && masterPassword != password) {
            Arrays.fill(masterPassword, '\0');
        }
        this.masterPassword = password;
    }

    // ── masterKey（主密钥，解锁后缓存） ──
    public SecretKey getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(SecretKey key) {
        this.masterKey = key;
    }

    public boolean isUnlocked() {
        return masterKey != null;
    }

    /**
     * 清除所有密钥材料（锁屏/退出时调用）。
     */
    public void clearAll() {
        if (masterPassword != null) {
            Arrays.fill(masterPassword, '\0');
            masterPassword = null;
        }
        if (masterKey != null) {
            try { masterKey.destroy(); } catch (Exception ignored) {}
            masterKey = null;
        }
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        clearAll();
    }
}
