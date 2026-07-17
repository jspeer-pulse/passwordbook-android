package com.example.passwordbook;

public class PasswordItem {
    private int id;
    private String platform;
    private String account;
    private String password;

    public PasswordItem(int id, String platform, String account, String password) {
        this.id = id;
        this.platform = platform;
        this.account = account;
        this.password = password;
    }

    public int getId() { return id; }
    public String getPlatform() { return platform; }
    public String getAccount() { return account; }
    public String getPassword() { return password; }
}
