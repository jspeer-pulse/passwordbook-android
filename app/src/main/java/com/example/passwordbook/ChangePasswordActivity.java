package com.example.passwordbook;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import javax.crypto.SecretKey;

public class ChangePasswordActivity extends AppCompatActivity {
    private EditText etOld, etNew, etConfirm;
    private Button btnSubmit;
    private boolean processing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_change_password);

        etOld = findViewById(R.id.et_old_password);
        etNew = findViewById(R.id.et_new_password);
        etConfirm = findViewById(R.id.et_confirm_password);
        btnSubmit = findViewById(R.id.btn_change_password);

        btnSubmit.setOnClickListener(v -> {
            if (processing) return;
            String oldPwd = etOld.getText().toString().trim();
            String newPwd = etNew.getText().toString().trim();
            String confirm = etConfirm.getText().toString().trim();

            if (oldPwd.isEmpty() || newPwd.isEmpty() || confirm.isEmpty()) {
                Toast.makeText(this, "所有字段不能为空", Toast.LENGTH_SHORT).show(); return;
            }
            if (newPwd.length() < 6) {
                Toast.makeText(this, "密码至少6位", Toast.LENGTH_SHORT).show(); return;
            }
            if (!newPwd.equals(confirm)) {
                Toast.makeText(this, "两次密码不一致", Toast.LENGTH_SHORT).show(); return;
            }

            processing = true;
            btnSubmit.setEnabled(false);
            char[] oldPwdArr = oldPwd.toCharArray();
            char[] newPwdArr = newPwd.toCharArray();

            new Thread(() -> {
                try {
                    SecretKey oldKey = PasswordStore.verifyAndDeriveKey(ChangePasswordActivity.this, oldPwdArr);
                    // Zero old password immediately after verification
                    CryptoHelper.zeroCharArray(oldPwdArr);
                    if (oldKey == null) {
                        CryptoHelper.zeroCharArray(newPwdArr);
                        runOnUiThread(() -> {
                            processing = false; btnSubmit.setEnabled(true);
                            Toast.makeText(ChangePasswordActivity.this, "旧密码错误", Toast.LENGTH_SHORT).show();
                            etOld.setText("");
                        });
                        return;
                    }
                    byte[] dbSalt = PasswordStore.getDbSalt(ChangePasswordActivity.this);
                    if (dbSalt == null) dbSalt = CryptoHelper.generateSalt();
                    int iterations = PasswordStore.getIterations(ChangePasswordActivity.this);
                    SecretKey newKey = CryptoHelper.deriveKey(newPwdArr, dbSalt, iterations);

                    PasswordDatabaseHelper dbHelper = new PasswordDatabaseHelper(ChangePasswordActivity.this);
                    MyApp app = (MyApp) getApplication();

                    // Step 0: Backup current token for crash recovery
                    PasswordStore.backupCurrentToken(ChangePasswordActivity.this);

                    // Step 1: Write new token FIRST (before touching DB)
                    if (!PasswordStore.updateForNewKey(ChangePasswordActivity.this, newKey)) {
                        PasswordStore.restoreBackupToken(ChangePasswordActivity.this);
                        CryptoHelper.zeroCharArray(newPwdArr);
                        runOnUiThread(() -> {
                            processing = false; btnSubmit.setEnabled(true);
                            Toast.makeText(ChangePasswordActivity.this, "修改失败，请重试", Toast.LENGTH_LONG).show();
                        });
                        return;
                    }

                    // Step 2: DB migration. If fails, revert token to oldKey.
                    if (!dbHelper.changeMasterPassword(oldKey, newKey, app.getMasterPassword())) {
                        PasswordStore.restoreBackupToken(ChangePasswordActivity.this);
                        CryptoHelper.zeroCharArray(newPwdArr);
                        runOnUiThread(() -> {
                            processing = false; btnSubmit.setEnabled(true);
                            Toast.makeText(ChangePasswordActivity.this, "修改失败，请重试", Toast.LENGTH_LONG).show();
                        });
                        return;
                    }

                    // Both succeeded — clear backup, update cache
                    PasswordStore.clearBackupToken(ChangePasswordActivity.this);
                    app.setMasterKey(newKey);
                    app.setMasterPassword(newPwdArr);  // newPwdArr now cached by MyApp

                    runOnUiThread(() -> {
                        processing = false; btnSubmit.setEnabled(true);
                        Toast.makeText(ChangePasswordActivity.this, "密码修改成功", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                } catch (Exception e) {
                    e.printStackTrace();
                    CryptoHelper.zeroCharArray(oldPwdArr);
                    CryptoHelper.zeroCharArray(newPwdArr);
                    runOnUiThread(() -> {
                        processing = false; btnSubmit.setEnabled(true);
                        Toast.makeText(ChangePasswordActivity.this, "修改失败，请重试", Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        etOld.setText(""); etNew.setText(""); etConfirm.setText("");
    }
}
