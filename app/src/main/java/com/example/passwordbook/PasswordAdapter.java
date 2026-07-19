package com.example.passwordbook;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class PasswordAdapter extends RecyclerView.Adapter<PasswordAdapter.ViewHolder> {
    private List<PasswordItem> data;
    private OnDeleteClickListener deleteListener;

    public interface OnDeleteClickListener {
        void onDeleteClick(PasswordItem item);
    }

    public PasswordAdapter(List<PasswordItem> data, OnDeleteClickListener listener) {
        this.data = data;
        this.deleteListener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_password, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PasswordItem item = data.get(position);
        holder.platformText.setText(item.getPlatform());

        SpannableStringBuilder acc = new SpannableStringBuilder();
        acc.append("账号：");
        acc.setSpan(new StyleSpan(Typeface.BOLD), 0, acc.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        acc.append(item.getAccount());
        holder.accountText.setText(acc);

        SpannableStringBuilder pwd = new SpannableStringBuilder();
        pwd.append("密码：");
        pwd.setSpan(new StyleSpan(Typeface.BOLD), 0, pwd.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        pwd.append(item.getPassword());
        holder.passwordText.setText(pwd);
        holder.deleteBtn.setOnClickListener(v -> {
            if (deleteListener != null) {
                deleteListener.onDeleteClick(item);
            }
        });
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    public void updateData(List<PasswordItem> newData) {
        this.data = newData;
        notifyDataSetChanged();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView platformText, accountText, passwordText;
        androidx.appcompat.widget.AppCompatButton deleteBtn;

        ViewHolder(View itemView) {
            super(itemView);
            platformText = itemView.findViewById(R.id.tv_platform);
            accountText = itemView.findViewById(R.id.tv_account);
            passwordText = itemView.findViewById(R.id.tv_password);
            deleteBtn = itemView.findViewById(R.id.btn_delete);
        }
    }
}
