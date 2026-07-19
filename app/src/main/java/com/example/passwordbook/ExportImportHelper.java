package com.example.passwordbook;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;

/**
 * Handles export/import of encrypted password data for device transfer.
 *
 * Export format (JSON):
 * {
 *   "version": 1,
 *   "app": "PasswordBook",
 *   "db_salt": "<base64>",
 *   "iterations": 200000,
 *   "encrypted_valid": "<base64 V2>",
 *   "entries": [
 *     {"platform": "plaintext", "account": "<V2 base64>", "password": "<V2 base64>"}
 *   ]
 * }
 *
 * All entry data is ALREADY ENCRYPTED. The export file without the master password
 * is cryptographically useless.
 */
public class ExportImportHelper {
    private static final String PREFS_NAME = "password_prefs";
    private static final String KEY_DB_SALT = "db_salt";
    private static final String KEY_ITERATIONS = "iterations";
    private static final String KEY_ENCRYPTED_VALID = "encrypted_valid";

    // ── Export ────────────────────────────────────
    public static String exportToJson(Context context, PasswordDatabaseHelper dbHelper) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String dbSalt = prefs.getString(KEY_DB_SALT, null);
        int iterations = prefs.getInt(KEY_ITERATIONS, CryptoHelper.ITERATIONS);
        String token = prefs.getString(KEY_ENCRYPTED_VALID, null);
        List<PasswordDatabaseHelper.RawEntry> entries = dbHelper.getRawEntries();

        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("app", "PasswordBook");
        root.put("db_salt", dbSalt);
        root.put("iterations", iterations);
        root.put("encrypted_valid", token);

        JSONArray arr = new JSONArray();
        for (PasswordDatabaseHelper.RawEntry e : entries) {
            JSONObject entry = new JSONObject();
            entry.put("platform", e.platform);
            entry.put("account", e.encAccount);
            entry.put("password", e.encPassword);
            arr.put(entry);
        }
        root.put("entries", arr);

        return root.toString(2);
    }

    public static ImportResult importFromJson(Context context,
                                               PasswordDatabaseHelper dbHelper,
                                               String json,
                                               char[] password) {
        try {
            JSONObject root = new JSONObject(json);

            // Read crypto parameters from export
            String saltB64 = root.getString("db_salt");
            int iterations = root.getInt("iterations");
            String token = root.getString("encrypted_valid");
            byte[] dbSalt = Base64.decode(saltB64, Base64.DEFAULT);

            // Verify password by deriving key and decrypting token
            SecretKey key;
            try {
                key = CryptoHelper.deriveKey(password, dbSalt, iterations);
                String decrypted = CryptoHelper.decryptWithKey(key, token);
                if (!"Valid".equals(decrypted)) {
                    return ImportResult.WRONG_PASSWORD;
                }
            } catch (Exception e) {
                return ImportResult.WRONG_PASSWORD;
            }

            // Parse entries
            JSONArray arr = root.getJSONArray("entries");
            List<PasswordDatabaseHelper.RawEntry> entries = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                PasswordDatabaseHelper.RawEntry e = new PasswordDatabaseHelper.RawEntry();
                e.platform = obj.getString("platform");
                e.encAccount = obj.getString("account");
                e.encPassword = obj.getString("password");
                entries.add(e);
            }

            // Backup current SharedPreferences in case DB replacement fails
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String oldSaltB64 = prefs.getString(KEY_DB_SALT, null);
            int oldIterations = prefs.getInt(KEY_ITERATIONS, CryptoHelper.ITERATIONS);
            String oldToken = prefs.getString(KEY_ENCRYPTED_VALID, null);

            // Step 1: Write new SharedPreferences FIRST (before touching DB).
            // If this fails, DB is untouched — safe to abort.
            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(KEY_DB_SALT, saltB64);
            editor.putInt(KEY_ITERATIONS, iterations);
            editor.putString(KEY_ENCRYPTED_VALID, token);
            if (!editor.commit()) {
                return ImportResult.PARSE_ERROR;
            }

            // Step 2: Replace database entries.
            // If this fails, revert SharedPreferences to old values.
            try {
                dbHelper.replaceAllEntries(entries);
            } catch (Exception dbEx) {
                // DB replacement failed — restore old SharedPreferences
                SharedPreferences.Editor revertEditor = prefs.edit();
                if (oldSaltB64 != null) revertEditor.putString(KEY_DB_SALT, oldSaltB64);
                else revertEditor.remove(KEY_DB_SALT);
                revertEditor.putInt(KEY_ITERATIONS, oldIterations);
                if (oldToken != null) revertEditor.putString(KEY_ENCRYPTED_VALID, oldToken);
                else revertEditor.remove(KEY_ENCRYPTED_VALID);
                revertEditor.commit();
                dbEx.printStackTrace();
                return ImportResult.PARSE_ERROR;
            }

            // Both succeeded — update MyApp cache
            MyApp app = (MyApp) context.getApplicationContext();
            app.setMasterKey(key);
            app.setMasterPassword(password);

            return ImportResult.SUCCESS;
        } catch (Exception e) {
            e.printStackTrace();
            return ImportResult.PARSE_ERROR;
        }
    }

    public enum ImportResult {
        SUCCESS,
        WRONG_PASSWORD,
        PARSE_ERROR
    }
}
