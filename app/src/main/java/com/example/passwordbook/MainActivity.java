package com.example.passwordbook;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private EditText etPlatform, etAccount, etPassword;
    private RecyclerView recyclerView;
    private PasswordAdapter adapter;
    private PasswordDatabaseHelper dbHelper;

    // 额外顶部间距（单位 dp），设为 0 表示紧贴状态栏下方
    private static final int EXTRA_SPACE_DP = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        LinearLayout rootLayout = findViewById(R.id.rootLayout);

        // 方法1：使用 WindowInsets 动态适配（推荐）
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int extraSpace = (int) (EXTRA_SPACE_DP * getResources().getDisplayMetrics().density);
            int totalPadding = statusBarHeight + extraSpace;

            v.setPadding(
                    v.getPaddingLeft(),
                    totalPadding,
                    v.getPaddingRight(),
                    v.getPaddingBottom()
            );

            Log.d("MainActivity", "状态栏高度=" + statusBarHeight + ", 额外间距=" + extraSpace + ", 总padding=" + totalPadding);
            return insets;
        });

        // 强制触发一次 insets 分发
        ViewCompat.requestApplyInsets(rootLayout);

        // 备用：post 延迟确保布局已测量（防止某些情况下未触发）
        rootLayout.post(() -> {
            // 如果上面的监听器未触发，这里再设置一次（但一般不会）
            // 但为了保险，可以二次确认
        });

        // 初始化视图
        etPlatform = findViewById(R.id.et_platform);
        etAccount = findViewById(R.id.et_account);
        etPassword = findViewById(R.id.et_password);
        recyclerView = findViewById(R.id.recycler_view);
        MaterialButton btnSave = findViewById(R.id.btn_save);

        dbHelper = new PasswordDatabaseHelper(this);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PasswordAdapter(getAllPasswords(), id -> {
            dbHelper.deletePassword(id);
            refreshList();
        });
        recyclerView.setAdapter(adapter);

        btnSave.setOnClickListener(v -> {
            String platform = etPlatform.getText().toString().trim();
            String account = etAccount.getText().toString().trim();
            String password = etPassword.getText().toString().trim();
            if (!TextUtils.isEmpty(platform) && !TextUtils.isEmpty(account) && !TextUtils.isEmpty(password)) {
                dbHelper.addPassword(platform, account, password);
                etPlatform.setText("");
                etAccount.setText("");
                etPassword.setText("");
                refreshList();
            }
        });
    }

    private void refreshList() {
        List<PasswordItem> list = getAllPasswords();
        adapter.updateData(list);
    }

    private List<PasswordItem> getAllPasswords() {
        return dbHelper.getAllPasswords();
    }
}