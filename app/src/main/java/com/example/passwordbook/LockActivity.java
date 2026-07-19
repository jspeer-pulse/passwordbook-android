package com.example.passwordbook;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import javax.crypto.SecretKey;

public class LockActivity extends AppCompatActivity {
    private EditText etPassword, etConfirm;
    private TextInputLayout tilConfirm;
    private TextView tvHint, tvChangePassword;
    private Button btnAction;
    private boolean isFirstTime;
    private boolean processing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        MyApp app = (MyApp) getApplication();
        if (app.isUnlocked()) {
            gotoMain();
            return;
        }

        // Detect interrupted password change — verifyAndDeriveKey will
        // automatically try both primary and backup tokens, no manual recovery needed.
        if (PasswordStore.detectInterruptedChange(this)) {
            Toast.makeText(this, "检测到上次密码修改未完成，请输入密码继续",
                    Toast.LENGTH_LONG).show();
        }

        etPassword = findViewById(R.id.et_password);
        etConfirm = findViewById(R.id.et_confirm);
        tilConfirm = findViewById(R.id.til_confirm);
        tvHint = findViewById(R.id.tv_hint);
        tvChangePassword = findViewById(R.id.tv_change_password);
        btnAction = findViewById(R.id.btn_action);

        isFirstTime = PasswordStore.isFirstTime(this);

        if (isFirstTime) {
            tilConfirm.setVisibility(View.VISIBLE);
            tvHint.setText("首次使用，请设置主密码");
            btnAction.setText("设置密码");
            tvChangePassword.setVisibility(View.GONE);
        } else {
            tilConfirm.setVisibility(View.GONE);
            tvHint.setText("请输入主密码");
            btnAction.setText("解锁");
            tvChangePassword.setVisibility(View.VISIBLE);
        }

        btnAction.setOnClickListener(v -> handleAction());
        tvChangePassword.setOnClickListener(v -> showChangePasswordDialog());
    }

    private void handleAction() {
        if (processing) return;
        final String password = etPassword.getText().toString().trim();
        if (password.isEmpty()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show();
            return;
        }
        if (isFirstTime) {
            String confirm = etConfirm.getText().toString().trim();
            if (!password.equals(confirm)) {
                Toast.makeText(this, "两次密码不一致", Toast.LENGTH_SHORT).show();
                return;
            }
            if (password.length() < 6) {
                Toast.makeText(this, "密码至少6位", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        processing = true;
        btnAction.setEnabled(false);
        final char[] pwd = password.toCharArray();

        new Thread(() -> {
            try {
                MyApp app = (MyApp) getApplication();
                SecretKey key;
                if (isFirstTime) {
                    key = PasswordStore.setupMasterPassword(LockActivity.this, pwd);
                } else {
                    key = PasswordStore.verifyAndDeriveKey(LockActivity.this, pwd);
                }
                if (key != null) {
                    app.setMasterKey(key);
                    app.setMasterPassword(pwd);
                    runOnUiThread(() -> { btnAction.setEnabled(true); processing = false; gotoMain(); });
                } else {
                    runOnUiThread(() -> {
                        btnAction.setEnabled(true); processing = false;
                        Toast.makeText(LockActivity.this, "密码错误", Toast.LENGTH_SHORT).show();
                        etPassword.setText("");
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    btnAction.setEnabled(true); processing = false;
                    Toast.makeText(LockActivity.this,
                            isFirstTime ? "设置失败，请重试" : "验证失败，请重试", Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void showChangePasswordDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_change_password, null);
        EditText etOldPassword = dialogView.findViewById(R.id.dialog_old_password);
        EditText etNewPassword = dialogView.findViewById(R.id.dialog_new_password);
        EditText etConfirmPassword = dialogView.findViewById(R.id.dialog_confirm_password);
        MaterialButton btnCancel = dialogView.findViewById(R.id.dialog_btn_cancel);
        MaterialButton btnConfirm = dialogView.findViewById(R.id.dialog_btn_confirm);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setView(dialogView)
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            String oldPwd = etOldPassword.getText().toString().trim();
            String newPwd = etNewPassword.getText().toString().trim();
            String confirmPwd = etConfirmPassword.getText().toString().trim();
            if (oldPwd.isEmpty() || newPwd.isEmpty() || confirmPwd.isEmpty()) {
                Toast.makeText(this, "所有字段不能为空", Toast.LENGTH_SHORT).show(); return;
            }
            if (!newPwd.equals(confirmPwd)) {
                Toast.makeText(this, "两次新密码不一致", Toast.LENGTH_SHORT).show(); return;
            }
            if (newPwd.length() < 6) {
                Toast.makeText(this, "密码至少6位", Toast.LENGTH_SHORT).show(); return;
            }
            if (oldPwd.equals(newPwd)) {
                Toast.makeText(this, "新密码不能与旧密码相同", Toast.LENGTH_SHORT).show(); return;
            }
            dialog.dismiss();
            performChangePassword(oldPwd.toCharArray(), newPwd.toCharArray());
        });
    }

    private void performChangePassword(char[] oldPwd, char[] newPwd) {
        Toast.makeText(this, "正在重新加密...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                SecretKey oldKey = PasswordStore.verifyAndDeriveKey(LockActivity.this, oldPwd);
                // Zero old password immediately after verification
                CryptoHelper.zeroCharArray(oldPwd);
                if (oldKey == null) {
                    runOnUiThread(() -> Toast.makeText(LockActivity.this, "旧密码错误", Toast.LENGTH_SHORT).show());
                    CryptoHelper.zeroCharArray(newPwd);
                    return;
                }
                byte[] dbSalt = PasswordStore.getDbSalt(LockActivity.this);
                if (dbSalt == null) dbSalt = CryptoHelper.generateSalt();
                int iterations = PasswordStore.getIterations(LockActivity.this);
                SecretKey newKey = CryptoHelper.deriveKey(newPwd, dbSalt, iterations);

                PasswordDatabaseHelper dbHelper = new PasswordDatabaseHelper(LockActivity.this);
                MyApp app = (MyApp) getApplication();

                // Step 0: Backup current token — if app crashes mid-change,
                // this allows recovery via recoverFromInterruptedChange()
                PasswordStore.backupCurrentToken(LockActivity.this);

                // Step 1: Write new token FIRST (before touching DB).
                // If this fails, we abort — DB is still untouched.
                if (!PasswordStore.updateForNewKey(LockActivity.this, newKey)) {
                    PasswordStore.restoreBackupToken(LockActivity.this);
                    runOnUiThread(() -> Toast.makeText(LockActivity.this,
                            "修改失败（无法写入令牌），请重试", Toast.LENGTH_SHORT).show());
                    CryptoHelper.zeroCharArray(newPwd);
                    return;
                }

                // Step 2: DB migration. If this fails, revert token to oldKey.
                if (!dbHelper.changeMasterPassword(oldKey, newKey, app.getMasterPassword())) {
                    // Revert token — DB still has old-key-encrypted entries
                    PasswordStore.restoreBackupToken(LockActivity.this);
                    runOnUiThread(() -> Toast.makeText(LockActivity.this,
                            "修改失败，请重试", Toast.LENGTH_SHORT).show());
                    CryptoHelper.zeroCharArray(newPwd);
                    return;
                }

                // Both succeeded — clear backup, update cache
                PasswordStore.clearBackupToken(LockActivity.this);
                app.setMasterKey(newKey);
                app.setMasterPassword(newPwd);  // newPwd now cached by MyApp — not zeroed
                runOnUiThread(() -> Toast.makeText(LockActivity.this, "主密码修改成功", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                e.printStackTrace();
                CryptoHelper.zeroCharArray(newPwd);
                runOnUiThread(() -> Toast.makeText(LockActivity.this, "修改失败，请重试", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void gotoMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
