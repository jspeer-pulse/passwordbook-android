package com.example.passwordbook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

public class PasswordDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "passwords.db";
    private static final int VERSION = 2;
    private static final String TABLE = "passwords";
    private static final String COL_ID = "id";
    private static final String COL_PLATFORM = "platform";
    private static final String COL_ACCOUNT = "account";
    private static final String COL_PASSWORD = "password";

    private final Context context;

    public PasswordDatabaseHelper(Context context) {
        super(context, DB_NAME, null, VERSION);
        this.context = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        String sql = "CREATE TABLE " + TABLE + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_PLATFORM + " TEXT NOT NULL, " +
                COL_ACCOUNT + " TEXT NOT NULL, " +
                COL_PASSWORD + " TEXT NOT NULL)";
        db.execSQL(sql);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // V1→V2: V1 存储的是明文，无法迁移到加密格式，清空重建
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE);
            onCreate(db);
        }
    }

    // ──────────────────────────────────────────────
    // 增删改查（使用 MyApp 中缓存的密钥，毫秒级）
    // ──────────────────────────────────────────────
    public long addPassword(String platform, String account, String password) {
        MyApp app = (MyApp) context.getApplicationContext();
        SecretKey key = app.getMasterKey();
        if (key == null) return -1;

        try {
            String encAccount = CryptoHelper.encryptWithKey(key, account);
            String encPassword = CryptoHelper.encryptWithKey(key, password);

            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_PLATFORM, platform);
            values.put(COL_ACCOUNT, encAccount);
            values.put(COL_PASSWORD, encPassword);
            long id = db.insert(TABLE, null, values);
            db.close();
            return id;
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    public List<PasswordItem> getAllPasswords() {
        MyApp app = (MyApp) context.getApplicationContext();
        SecretKey key = app.getMasterKey();
        if (key == null) return new ArrayList<>();

        return getAllPasswordsWithKey(key, app.getMasterPassword());
    }

    public void deletePassword(int id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, COL_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }

    // ──────────────────────────────────────────────
    // 导出/导入：直接操作密文，不解密
    // ──────────────────────────────────────────────
    public static class RawEntry {
        public String platform;
        public String encAccount;   // already-encrypted V2 Base64
        public String encPassword;  // already-encrypted V2 Base64
    }

    public List<RawEntry> getRawEntries() {
        List<RawEntry> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE, null, null, null, null, null,
                COL_PLATFORM + " COLLATE NOCASE ASC");
        while (cursor.moveToNext()) {
            RawEntry e = new RawEntry();
            e.platform = cursor.getString(cursor.getColumnIndex(COL_PLATFORM));
            e.encAccount = cursor.getString(cursor.getColumnIndex(COL_ACCOUNT));
            e.encPassword = cursor.getString(cursor.getColumnIndex(COL_PASSWORD));
            list.add(e);
        }
        cursor.close();
        db.close();
        return list;
    }

    public void replaceAllEntries(List<RawEntry> entries) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE, null, null);
            for (RawEntry e : entries) {
                ContentValues values = new ContentValues();
                values.put(COL_PLATFORM, e.platform);
                values.put(COL_ACCOUNT, e.encAccount);
                values.put(COL_PASSWORD, e.encPassword);
                // insertOrThrow ensures silent failures (-1) become exceptions
                // → transaction rolls back → no data loss
                if (db.insertOrThrow(TABLE, null, values) < 0) {
                    throw new IllegalStateException("插入条目失败");
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        db.close();
    }

    // ──────────────────────────────────────────────
    // 修改主密码（全量重加密）
    // ──────────────────────────────────────────────
    /**
     * Re-encrypt all entries from oldKey to newKey.
     * legacyPassword is used as fallback for old-format entries (can be null).
     * All DB operations use a single connection — no nested open/close.
     */
    public boolean changeMasterPassword(SecretKey oldKey, SecretKey newKey, char[] legacyPassword) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        Cursor cursor = null;
        try {
            // Read and decrypt all entries inline (same db handle, no nested close)
            List<PasswordItem> items = new ArrayList<>();
            boolean allDecrypted = true;
            cursor = db.query(TABLE, null, null, null, null, null,
                    COL_PLATFORM + " COLLATE NOCASE ASC");
            while (cursor.moveToNext()) {
                int id = cursor.getInt(cursor.getColumnIndex(COL_ID));
                String platform = cursor.getString(cursor.getColumnIndex(COL_PLATFORM));
                String encAccount = cursor.getString(cursor.getColumnIndex(COL_ACCOUNT));
                String encPassword = cursor.getString(cursor.getColumnIndex(COL_PASSWORD));
                try {
                    String account = CryptoHelper.decryptWithKey(oldKey, encAccount);
                    String pwd = CryptoHelper.decryptWithKey(oldKey, encPassword);
                    items.add(new PasswordItem(id, platform, account, pwd));
                } catch (Exception e1) {
                    if (legacyPassword != null) {
                        try {
                            String account = CryptoHelper.decrypt(legacyPassword, encAccount);
                            String pwd = CryptoHelper.decrypt(legacyPassword, encPassword);
                            items.add(new PasswordItem(id, platform, account, pwd));
                        } catch (Exception e2) {
                            allDecrypted = false;
                        }
                    } else {
                        allDecrypted = false;
                    }
                }
            }
            cursor.close();
            cursor = null;

            if (!allDecrypted) {
                return false; // some entries unreadable → rollback entire transaction
            }

            if (items.isEmpty()) {
                db.setTransactionSuccessful();
                return true;
            }

            // Re-encrypt with new key
            for (PasswordItem item : items) {
                String newEncAccount = CryptoHelper.encryptWithKey(newKey, item.getAccount());
                String newEncPassword = CryptoHelper.encryptWithKey(newKey, item.getPassword());
                ContentValues values = new ContentValues();
                values.put(COL_ACCOUNT, newEncAccount);
                values.put(COL_PASSWORD, newEncPassword);
                int rows = db.update(TABLE, values, COL_ID + "=?",
                        new String[]{String.valueOf(item.getId())});
                if (rows != 1) {
                    return false;
                }
            }

            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            if (cursor != null) cursor.close();
            try { db.endTransaction(); } catch (Exception ignored) {}
            try { db.close(); } catch (Exception ignored) {}
        }
    }

    // ──────────────────────────────────────────────
    // 内部方法
    // ──────────────────────────────────────────────
    /**
     * 用密钥解密所有条目。若某条解密失败且 legacyPassword 不为空，
     * 则尝试用旧密码解密（兼容 V1/Legacy 格式），成功后自动重新加密为 V2 格式。
     * <p>
     * Migration entries are collected during the cursor loop and batch-written
     * AFTER the cursor is closed, avoiding "database is locked" or cursor
     * invalidation from writes inside iteration.
     */
    private List<PasswordItem> getAllPasswordsWithKey(SecretKey key, char[] legacyPassword) {
        List<PasswordItem> list = new ArrayList<>();

        // Collect entries that need migration (V1/Legacy → V2)
        // We defer writes until after the cursor is closed.
        List<PasswordItem> toMigrate = new ArrayList<>();

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE, null, null, null, null, null,
                COL_PLATFORM + " COLLATE NOCASE ASC");

        while (cursor.moveToNext()) {
            int id = cursor.getInt(cursor.getColumnIndex(COL_ID));
            String platform = cursor.getString(cursor.getColumnIndex(COL_PLATFORM));
            String encAccount = cursor.getString(cursor.getColumnIndex(COL_ACCOUNT));
            String encPassword = cursor.getString(cursor.getColumnIndex(COL_PASSWORD));

            try {
                // 优先用密钥解密（V2 格式）
                String account = CryptoHelper.decryptWithKey(key, encAccount);
                String pwd = CryptoHelper.decryptWithKey(key, encPassword);
                list.add(new PasswordItem(id, platform, account, pwd));
            } catch (Exception e1) {
                // V2 失败，尝试用旧密码解密
                if (legacyPassword != null) {
                    try {
                        String account = CryptoHelper.decrypt(legacyPassword, encAccount);
                        String pwd = CryptoHelper.decrypt(legacyPassword, encPassword);
                        PasswordItem item = new PasswordItem(id, platform, account, pwd);
                        list.add(item);
                        toMigrate.add(item); // defer migration until cursor closed
                    } catch (Exception e2) {
                        // 两种方式都失败，跳过该条目
                        e2.printStackTrace();
                    }
                }
            }
        }
        cursor.close();
        db.close();

        // Batch-migrate legacy entries outside the cursor loop
        if (!toMigrate.isEmpty()) {
            batchMigrateToV2(toMigrate, key);
        }

        return list;
    }

    /** Batch-re-encrypt legacy entries with the V2 key. Called outside any cursor loop. */
    private void batchMigrateToV2(List<PasswordItem> items, SecretKey key) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (PasswordItem item : items) {
                String encAccount = CryptoHelper.encryptWithKey(key, item.getAccount());
                String encPassword = CryptoHelper.encryptWithKey(key, item.getPassword());
                ContentValues values = new ContentValues();
                values.put(COL_ACCOUNT, encAccount);
                values.put(COL_PASSWORD, encPassword);
                db.update(TABLE, values, COL_ID + "=?",
                        new String[]{String.valueOf(item.getId())});
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            android.util.Log.e("PasswordBook", "批量迁移到V2失败", e);
        } finally {
            db.endTransaction();
            db.close();
        }
    }

}
