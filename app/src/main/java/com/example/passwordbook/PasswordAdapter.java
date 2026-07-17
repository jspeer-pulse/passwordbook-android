package com.example.passwordbook;

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
        void onDeleteClick(int id);
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
        holder.accountText.setText("Account: " + item.getAccount());
        holder.passwordText.setText("Password: " + item.getPassword());
        holder.deleteBtn.setOnClickListener(v -> {
            if (deleteListener != null) {
                deleteListener.onDeleteClick(item.getId());
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
