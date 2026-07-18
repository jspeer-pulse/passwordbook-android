package com.example.passwordbook;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.textfield.TextInputLayout;

public class LockActivity extends AppCompatActivity {
    private EditText etPassword, etConfirm;
    private TextInputLayout tilConfirm;
    private TextView tvHint;
    private Button btnAction;
    private boolean isFirstTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        // ---- 如果进程还活着（已解锁），直接进入主界面 ----
        MyApp app = (MyApp) getApplication();
        if (app.isUnlocked()) {
            gotoMain();
            return;
        }

        etPassword = findViewById(R.id.et_password);
        etConfirm = findViewById(R.id.et_confirm);
        tilConfirm = findViewById(R.id.til_confirm);
        tvHint = findViewById(R.id.tv_hint);
        btnAction = findViewById(R.id.btn_action);

        isFirstTime = PasswordStore.isFirstTime(this);

        if (isFirstTime) {
            tilConfirm.setVisibility(View.VISIBLE);
            tvHint.setText("首次使用，请设置启动密码");
            btnAction.setText("设置密码");
        } else {
            tilConfirm.setVisibility(View.GONE);
            tvHint.setText("请输入启动密码");
            btnAction.setText("解锁");
        }

        btnAction.setOnClickListener(v -> handleAction());
    }

    private void handleAction() {
        String password = etPassword.getText().toString().trim();

        if (password.isEmpty()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isFirstTime) {
            // ---- 首次设置密码 ----
            String confirm = etConfirm.getText().toString().trim();
            if (!password.equals(confirm)) {
                Toast.makeText(this, "两次密码不一致", Toast.LENGTH_SHORT).show();
                return;
            }
            if (password.length() < 6) {
                Toast.makeText(this, "密码至少6位", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                PasswordStore.saveEncryptedValid(this, password.toCharArray());
                Toast.makeText(this, "密码设置成功", Toast.LENGTH_SHORT).show();
                // 缓存密码到 Application
                ((MyApp) getApplication()).setMasterPassword(password.toCharArray());
                gotoMain();
            } catch (Exception e) {
                Toast.makeText(this, "设置失败，请重试", Toast.LENGTH_SHORT).show();
            }
        } else {
            // ---- 验证密码 ----
            if (PasswordStore.verifyPassword(this, password.toCharArray())) {
                // 缓存密码到 Application，进程存活期间无需再次输入
                ((MyApp) getApplication()).setMasterPassword(password.toCharArray());
                Toast.makeText(this, "解锁成功", Toast.LENGTH_SHORT).show();
                gotoMain();
            } else {
                Toast.makeText(this, "密码错误", Toast.LENGTH_SHORT).show();
                etPassword.setText("");
            }
        }
    }

    private void gotoMain() {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}
