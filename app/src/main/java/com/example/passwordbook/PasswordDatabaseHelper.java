package com.example.passwordbook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class PasswordDatabaseHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "passwords.db";
    private static final int VERSION = 1;
    private static final String TABLE = "passwords";
    private static final String COL_ID = "id";
    private static final String COL_PLATFORM = "platform";
    private static final String COL_ACCOUNT = "account";
    private static final String COL_PASSWORD = "password";

    public PasswordDatabaseHelper(Context context) {
        super(context, DB_NAME, null, VERSION);
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
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    public long addPassword(String platform, String account, String password) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_PLATFORM, platform);
        values.put(COL_ACCOUNT, account);
        values.put(COL_PASSWORD, password);
        long id = db.insert(TABLE, null, values);
        db.close();
        return id;
    }

    public List<PasswordItem> getAllPasswords() {
        List<PasswordItem> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.query(TABLE, null, null, null, null, null, COL_PLATFORM + " COLLATE NOCASE ASC");
        while (cursor.moveToNext()) {
            int id = cursor.getInt(cursor.getColumnIndex(COL_ID));
            String platform = cursor.getString(cursor.getColumnIndex(COL_PLATFORM));
            String account = cursor.getString(cursor.getColumnIndex(COL_ACCOUNT));
            String password = cursor.getString(cursor.getColumnIndex(COL_PASSWORD));
            list.add(new PasswordItem(id, platform, account, password));
        }
        cursor.close();
        db.close();
        return list;
    }

    public void deletePassword(int id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE, COL_ID + "=?", new String[]{String.valueOf(id)});
        db.close();
    }
}
