package com.example.passwordbook;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;


public class MainActivity extends AppCompatActivity {
    // UI 组件
    private EditText etPlatform, etAccount, etPassword;
    private RecyclerView recyclerView;
    private PasswordAdapter adapter;
    private PasswordDatabaseHelper dbHelper;

    // 额外顶部间距（单位 dp），设为 0 表示紧贴状态栏下方；可调整以适配设计需求
    private static final int EXTRA_SPACE_DP = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ---- 未解锁保护：进程被杀死后重建时，跳回锁屏 ----
        MyApp app = (MyApp) getApplication();
        if (!app.isUnlocked()) {
            android.content.Intent intent = new android.content.Intent(this, LockActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        // 获取根布局，用于动态设置内边距以避开状态栏
        LinearLayout rootLayout = findViewById(R.id.rootLayout);


        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            // 获取状态栏高度（像素）
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            // 将额外间距从 dp 转换为像素
            int extraSpace = (int) (EXTRA_SPACE_DP * getResources().getDisplayMetrics().density);
            int totalPadding = statusBarHeight + extraSpace;

            // 仅修改顶部内边距，保留原有的左右和底部内边距
            v.setPadding(
                    v.getPaddingLeft(),
                    totalPadding,
                    v.getPaddingRight(),
                    v.getPaddingBottom()
            );

            // 调试日志，便于检查适配效果
            Log.d("MainActivity", "状态栏高度=" + statusBarHeight + ", 额外间距=" + extraSpace + ", 总padding=" + totalPadding);
            return insets; // 继续传递 insets，以便其他监听器或系统处理
        });


        ViewCompat.requestApplyInsets(rootLayout);

        // 备用措施：post 延迟确保布局已测量（正常情况下监听器会触发，此段仅作安全兜底）
        rootLayout.post(() -> {
            // 实际项目中，如果发现监听器未触发，可在此添加二次设置逻辑
            // 但当前实现已覆盖大多数场景，此处保留空实现以便后续扩展
        });

        // ---------- 初始化视图 ----------
        etPlatform = findViewById(R.id.et_platform);
        etAccount = findViewById(R.id.et_account);
        etPassword = findViewById(R.id.et_password);
        recyclerView = findViewById(R.id.recycler_view);
        MaterialButton btnSave = findViewById(R.id.btn_save);

        // 初始化数据库帮助类
        dbHelper = new PasswordDatabaseHelper(this);

        // 配置 RecyclerView：线性布局管理器
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // 创建适配器，传入初始数据（从数据库加载），并设置删除回调
        adapter = new PasswordAdapter(getAllPasswords(), item -> {
            // 加载自定义布局
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_delete, null);
            TextView tvPlatform = dialogView.findViewById(R.id.dialog_platform);
            TextView tvAccount = dialogView.findViewById(R.id.dialog_account);
            TextView tvPassword = dialogView.findViewById(R.id.dialog_password);

            tvPlatform.setText("平台：" + item.getPlatform());
            tvAccount.setText("账号：" + item.getAccount());
            tvPassword.setText("密码：" + item.getPassword());

            new MaterialAlertDialogBuilder(this)
                    .setView(dialogView)
                    .setPositiveButton("删除", (dialog, which) -> {
                        dbHelper.deletePassword(item.getId());
                        refreshList();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        recyclerView.setAdapter(adapter);

        // ---------- 保存按钮点击事件 ----------
        btnSave.setOnClickListener(v -> {

            String platform = etPlatform.getText().toString().trim();
            String account = etAccount.getText().toString().trim();
            String password = etPassword.getText().toString().trim();

            // 简单校验：三个字段均不能为空
            if (!TextUtils.isEmpty(platform) && !TextUtils.isEmpty(account) && !TextUtils.isEmpty(password)) {
                // 插入数据库
                dbHelper.addPassword(platform, account, password);
                // 清空输入框，便于连续录入
                etPlatform.setText("");
                etAccount.setText("");
                etPassword.setText("");
                // 刷新列表显示最新数据
                refreshList();
            }
            // 若有空字段，静默忽略（实际项目可添加 Toast 提示）
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