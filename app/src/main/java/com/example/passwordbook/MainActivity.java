package com.example.passwordbook;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;


public class MainActivity extends AppCompatActivity {
    private EditText etPlatform, etAccount, etPassword;
    private RecyclerView recyclerView;
    private PasswordAdapter adapter;
    private PasswordDatabaseHelper dbHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        MyApp app = (MyApp) getApplication();
        if (!app.isUnlocked()) {
            Intent intent = new Intent(this, LockActivity.class);
            startActivity(intent);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        LinearLayout rootLayout = findViewById(R.id.rootLayout);

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(
                    v.getPaddingLeft(), statusBarHeight,
                    v.getPaddingRight(), v.getPaddingBottom());
            Log.d("MainActivity", "statusBar=" + statusBarHeight);
            return insets;
        });

        ViewCompat.requestApplyInsets(rootLayout);

        etPlatform = findViewById(R.id.et_platform);
        etAccount = findViewById(R.id.et_account);
        etPassword = findViewById(R.id.et_password);
        recyclerView = findViewById(R.id.recycler_view);
        MaterialButton btnSave = findViewById(R.id.btn_save);
        MaterialButton btnTransfer = findViewById(R.id.btn_transfer);

        dbHelper = new PasswordDatabaseHelper(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new PasswordAdapter(getAllPasswords(), item -> {
            View dialogView = getLayoutInflater().inflate(R.layout.dialog_delete, null);
            TextView tvPlatform = dialogView.findViewById(R.id.dialog_platform);
            TextView tvAccount = dialogView.findViewById(R.id.dialog_account);
            TextView tvPassword = dialogView.findViewById(R.id.dialog_password);
            MaterialButton btnCancel = dialogView.findViewById(R.id.dialog_btn_cancel);
            MaterialButton btnDelete = dialogView.findViewById(R.id.dialog_btn_delete);

            SpannableStringBuilder plat = new SpannableStringBuilder();
            plat.append("平台：");
            plat.setSpan(new StyleSpan(Typeface.BOLD), 0, plat.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            plat.append(item.getPlatform());
            tvPlatform.setText(plat);

            SpannableStringBuilder acc = new SpannableStringBuilder();
            acc.append("账号：");
            acc.setSpan(new StyleSpan(Typeface.BOLD), 0, acc.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            acc.append(item.getAccount());
            tvAccount.setText(acc);

            SpannableStringBuilder pwd = new SpannableStringBuilder();
            pwd.append("密码：");
            pwd.setSpan(new StyleSpan(Typeface.BOLD), 0, pwd.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pwd.append(item.getPassword());
            tvPassword.setText(pwd);

            final androidx.appcompat.app.AlertDialog dialog =
                    new MaterialAlertDialogBuilder(this)
                    .setView(dialogView)
                    .create();

            dialog.setCanceledOnTouchOutside(false);

            btnCancel.setOnClickListener(v -> dialog.dismiss());
            btnDelete.setOnClickListener(v -> {
                dbHelper.deletePassword(item.getId());
                refreshList();
                dialog.dismiss();
            });

            dialog.show();
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

        btnTransfer.setOnClickListener(v ->
                startActivity(new Intent(this, QrTransferActivity.class)));
    }

    // ── Data ───────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        List<PasswordItem> list = getAllPasswords();
        adapter.updateData(list);
    }

    private List<PasswordItem> getAllPasswords() {
        return dbHelper.getAllPasswords();
    }
}
