# 🔐 PasswordBook

> Android 加密密码管理器，支持本地加密存储与设备间二维码安全传输。

## ✨ 功能

- 🔑 **主密码保护** — 首次使用设置主密码（≥6 位），每次冷启动需解锁
- 🛡️ **全字段加密** — 账号和密码使用 AES-256-GCM 加密存储，每条记录独立随机 IV
- 📋 **增删管理** — 添加 / 删除密码条目，RecyclerView 列表展示
- 🔄 **修改主密码** — 后台线程全量重加密所有条目，带崩溃恢复机制
- 📲 **二维码传输** — ECDH P-256 双向密钥交换，扫码即可在两台设备间安全传输数据

## 🛠️ 技术栈

| 层面 | 技术 |
|------|------|
| 📱 平台 | Android，Java 11，minSdk 24，targetSdk 36 |
| 🗄️ 数据库 | SQLite |
| 🔐 密钥派生 | PBKDF2-HMAC-SHA256，200,000 次迭代 |
| 🔒 数据加密 | AES-256-GCM，128-bit 认证标签 |
| 🤝 传输加密 | ECDH secp256r1 + SHA-256 派生会话密钥 + AES-256-GCM |
| 📷 二维码 | ZXing Core 3.5.3 + zxing-android-embedded 4.3.0 |
| 🏗️ 构建 | Gradle KTS |

## 🏛️ 安全架构

```
master_password + db_salt(16B)
        │
        ▼
PBKDF2-HMAC-SHA256 (200,000 次迭代)
        │
        ▼
   master_key (AES-256)  ← 解锁时派生一次，缓存于 MyApp
        │
        ├── 验证令牌：AES-256-GCM("Valid") → SharedPreferences
        │
        └── 每条数据库记录：AES-256-GCM(account/password) → SQLite
            每个条目使用独立随机 IV，相同明文产生不同密文
```

- 迭代次数在首次设置时写入 SharedPreferences，修改代码常量不会影响已有数据
- 平台名明文存储以便排序检索，账号和密码全密文
- 支持旧格式（V1/Legacy）自动迁移到当前 V2 格式

## 📲 QR 传输协议

纯 ECDH 双向密钥交换，双方私钥从不离开各自设备，窃听者无法破解。

```
接收方                                  发送方
  │ 📸 摄像头打开                          │
  │                                        │ 📤 生成 (da, Qa = da·G)
  │                                        │ 📱 显示 Qa 二维码
  │ ←━━ 扫描 Qa ━━━━━━━━━━━━━━━━━━━━━━ │
  │ 生成 (db, Qb = db·G)                   │
  │ K = SHA-256(domain || db·Qa)          │
  │ 📱 显示 Qb 二维码                       │
  │                                        │ 📸 摄像头打开
  │ ━━━━ 扫描 Qb ━━━━━━━━━━━━━━━━━━━━━→ │
  │                                        │ K = SHA-256(domain || da·Qb)
  │                                        │ AES-GCM(K, data) → 数据帧
  │                                        │ 📱 显示数据帧二维码
  │ 📸 摄像头打开                           │
  │ ←━━ 扫描数据帧 ━━━━━━━━━━━━━━━━━━━━ │
  │ 🔓 解密，导入                           │              ✅ 完成
```

- 🔐 双方私钥不出设备，窃听者无法从公钥计算 `da·db·G`（ECDLP）
- 🗑️ 会话密钥一次性使用，传输完成后立即清零
- 🎲 每帧独立随机 IV

## 📁 项目结构

```
app/src/main/java/com/example/passwordbook/
├── MainActivity.java              # 🏠 主界面：列表 + 添加/删除 + 传输入口
├── LockActivity.java              # 🔐 锁屏：首次设置/验证/修改主密码
├── ChangePasswordActivity.java    # 🔄 独立修改密码页面
├── QrTransferActivity.java        # 📲 QR 传输：发送/接收
├── QrTransferCrypto.java          # 🤝 ECDH P-256 + 分帧加解密
├── MyApp.java                     # 💾 Application：缓存密钥
├── CryptoHelper.java              # 🔒 PBKDF2 + AES-256-GCM
├── PasswordDatabaseHelper.java    # 🗄️ SQLite CRUD + 全量重加密
├── PasswordStore.java             # ⚙️ SharedPreferences 管理
├── ExportImportHelper.java        # 📦 JSON 导出/导入
├── PasswordItem.java              # 📋 数据模型
└── PasswordAdapter.java           # 🧩 RecyclerView 适配器

app/src/main/res/layout/
├── activity_main.xml
├── activity_lock.xml
├── activity_change_password.xml
├── activity_qr_transfer.xml
├── item_password.xml
├── dialog_delete.xml
└── dialog_change_password.xml
```

## 🚀 构建

```bash
./gradlew assembleDebug     # 调试构建
./gradlew assembleRelease   # 发布构建
```

需要 Android Studio 及 API 36 SDK。

## 📄 许可

仅供个人学习使用。
