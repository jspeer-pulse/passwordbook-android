package com.example.passwordbook;

import android.app.Application;

/**
 * Application 级别的密码缓存。
 * 只要进程存活，密码就保留在内存中，实现"不清后台就无需再次输入密码"。
 */
public class MyApp extends Application {
    private char[] masterPassword;

    public char[] getMasterPassword() {
        return masterPassword;
    }

    public void setMasterPassword(char[] password) {
        // 先擦除旧密码
        if (masterPassword != null) {
            for (int i = 0; i < masterPassword.length; i++) {
                masterPassword[i] = 0;
            }
        }
        this.masterPassword = password;
    }

    public boolean isUnlocked() {
        return masterPassword != null && masterPassword.length > 0;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        if (masterPassword != null) {
            for (int i = 0; i < masterPassword.length; i++) {
                masterPassword[i] = 0;
            }
        }
    }
}
