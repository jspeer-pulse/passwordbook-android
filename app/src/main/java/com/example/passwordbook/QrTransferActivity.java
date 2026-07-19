package com.example.passwordbook;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.crypto.SecretKey;

/**
 * QR code transfer — v5 pure ECDH bidirectional key exchange.
 *
 * <h3>Flow</h3>
 * <pre>
 *   Receiver                          Sender
 *     │ 点击"接收"                       │
 *     │ 摄像头打开                       │
 *     │                                 │ 点击"发送"
 *     │                                 │ 生成 (da, Qa=da·G)
 *     │                                 │ 显示 Qa 二维码
 *     │ ←━━ 扫描 Qa ━━━━━━━━━━━━━━━━━ │
 *     │ 得到 Qa                         │
 *     │ 生成 (db, Qb=db·G)              │
 *     │ 计算 K = SHA-256(domain||db·Qa)│
 *     │ 显示 Qb 二维码                   │
 *     │                                  │ 点击"下一步"
 *     │                                  │ 摄像头打开
 *     │ ━━━━ 扫描 Qb ━━━━━━━━━━━━━━━━→ │
 *     │                                  │ 得到 Qb
 *     │                                  │ 计算 K = SHA-256(domain||da·Qb)
 *     │                                  │ AES-GCM(K, data) → 数据帧
 *     │                                  │ 显示数据帧
 *     │ 点击"开始接收"                     │
 *     │ 摄像头打开                        │
 *     │ ←━━ 扫描数据帧 ━━━━━━━━━━━━━━━ │
 *     │ 解密, 导入                        │                done
 * </pre>
 */
public class QrTransferActivity extends AppCompatActivity {

    // ── Phase enum ───────────────────────────────────
    private enum Phase {
        IDLE,
        SEND_SHOW_PUBKEY,        // sender: showing da·G QR
        SEND_SCAN_PEER_PUBKEY,   // sender: scanning receiver's db·G
        SEND_SHOW_DATA,          // sender: showing encrypted data frames
        RECV_SCAN_PUBKEY,        // receiver: scanning sender's da·G (camera open immediately)
        RECV_SHOW_PUBKEY,        // receiver: showing db·G QR
        RECV_SCAN_DATA           // receiver: scanning data frames
    }

    // ── UI ───────────────────────────────────────────
    // Shared scanner
    private DecoratedBarcodeView scannerView;
    private TextView tvScannerStatus, tvScannerProgress;
    private MaterialButton btnScannerCancel;

    // Main content
    private LinearLayout layoutModeSelect;
    private TextView tvTitle, tvTransferHint;
    private MaterialButton btnSendMode, btnReceiveMode;

    // Pubkey QR display (reused by both sender and receiver)
    private LinearLayout layoutPubkeyQr;
    private ImageView ivPubkeyQr;
    private TextView tvPubkeyTitle, tvPubkeyHint;
    private MaterialButton btnPubkeyNext, btnCancelPubkey;

    // Send: data QR frames
    private LinearLayout layoutSendQr;
    private ImageView ivQrCode;
    private TextView tvFrameProgress, tvFrameHint;
    private MaterialButton btnNextFrame, btnTransferDone, btnCancelSend;

    // ── State ────────────────────────────────────────
    private PasswordDatabaseHelper dbHelper;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Phase phase = Phase.IDLE;
    private boolean transferComplete;

    // ECDH keys
    private KeyPair ourKeyPair;        // our ephemeral keypair
    private PublicKey peerPublicKey;   // scanned from peer's QR
    private SecretKey encryptionKey;   // K = SHA-256(domain || shared_secret)

    // Send state
    private List<QrTransferCrypto.Frame> sendFrames;
    private int currentFrameIdx;

    // Receive state
    private QrTransferCrypto.Frame[] receivedFrames;
    private int recvTotal;
    private int recvCount;
    private boolean scanning;

    // Camera permission
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) onCameraGranted();
                        else Toast.makeText(this, "需要摄像头权限", Toast.LENGTH_SHORT).show();
                    });

    // Which action is pending after camera permission
    private Runnable pendingAfterPermission;

    // ── Lifecycle ────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr_transfer);

        dbHelper = new PasswordDatabaseHelper(this);

        // Shared scanner
        scannerView = findViewById(R.id.scanner_view);
        scannerView.getBarcodeView().setDecoderFactory(
                new DefaultDecoderFactory(
                        Collections.singletonList(BarcodeFormat.QR_CODE)));
        tvScannerStatus = findViewById(R.id.tv_scanner_status);
        tvScannerProgress = findViewById(R.id.tv_scanner_progress);
        btnScannerCancel = findViewById(R.id.btn_scanner_cancel);

        // Main content
        layoutModeSelect = findViewById(R.id.layout_mode_select);
        tvTitle = findViewById(R.id.tv_title);
        tvTransferHint = findViewById(R.id.tv_transfer_hint);

        btnSendMode = findViewById(R.id.btn_send_mode);
        btnReceiveMode = findViewById(R.id.btn_receive_mode);

        // Pubkey display (was layout_recv_pubkey — now reused by both roles)
        layoutPubkeyQr = findViewById(R.id.layout_recv_pubkey);
        ivPubkeyQr = findViewById(R.id.iv_recv_pubkey_qr);
        tvPubkeyTitle = findViewById(R.id.tv_pubkey_title);
        tvPubkeyHint = findViewById(R.id.tv_pubkey_hint);
        btnPubkeyNext = findViewById(R.id.btn_recv_start_scan);
        btnCancelPubkey = findViewById(R.id.btn_cancel_recv_pubkey);

        // Send data QR display
        layoutSendQr = findViewById(R.id.layout_send_qr);
        ivQrCode = findViewById(R.id.iv_qr_code);
        tvFrameProgress = findViewById(R.id.tv_frame_progress);
        tvFrameHint = findViewById(R.id.tv_frame_hint);
        btnNextFrame = findViewById(R.id.btn_next_frame);
        btnTransferDone = findViewById(R.id.btn_transfer_done);
        btnCancelSend = findViewById(R.id.btn_cancel_send);

        // ── Clicks ────────────────────────────────────
        btnSendMode.setOnClickListener(v -> startSendShowPubkey());
        btnReceiveMode.setOnClickListener(v -> {
            pendingAfterPermission = this::startRecvScanPubkey;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            } else {
                startRecvScanPubkey();
            }
        });

        btnPubkeyNext.setOnClickListener(v -> onPubkeyNextClicked());
        btnCancelPubkey.setOnClickListener(v -> cancelTransfer());

        btnNextFrame.setOnClickListener(v -> advanceFrame());
        btnTransferDone.setOnClickListener(v -> finishTransfer());
        btnCancelSend.setOnClickListener(v -> cancelTransfer());

        btnScannerCancel.setOnClickListener(v -> cancelTransfer());
    }

    private void onCameraGranted() {
        if (pendingAfterPermission != null) {
            pendingAfterPermission.run();
            pendingAfterPermission = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scannerView != null && isScanningPhase()) {
            scannerView.resume();
            if (!transferComplete) resumeScanning();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (scannerView != null) scannerView.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        transferComplete = true;
        scanning = false;
        zeroAllSecrets();
    }

    @Override
    public void onBackPressed() {
        if (phase != Phase.IDLE) {
            cancelTransfer();
        } else {
            super.onBackPressed();
        }
    }

    // ── Helpers ──────────────────────────────────────

    private boolean isScanningPhase() {
        return phase == Phase.SEND_SCAN_PEER_PUBKEY
                || phase == Phase.RECV_SCAN_PUBKEY
                || phase == Phase.RECV_SCAN_DATA;
    }

    private void hideAll() {
        tvTransferHint.setVisibility(View.GONE);
        layoutModeSelect.setVisibility(View.GONE);
        layoutPubkeyQr.setVisibility(View.GONE);
        layoutSendQr.setVisibility(View.GONE);
        hideScanner();
    }

    private void showScanner() {
        findViewById(R.id.container_scanner).setVisibility(View.VISIBLE);
        scannerView.resume();
    }

    private void hideScanner() {
        scannerView.pause();
        findViewById(R.id.container_scanner).setVisibility(View.GONE);
    }

    private void resumeScanning() {
        if (scanning || transferComplete) return;
        scanning = true;
        scannerView.decodeSingle(callback);
    }

    // ── Barcode callback ─────────────────────────────

    private final BarcodeCallback callback = new BarcodeCallback() {
        @Override
        public void barcodeResult(BarcodeResult result) {
            scanning = false;
            if (transferComplete) return;
            if (result.getText() == null) {
                resumeScanning();
                return;
            }
            handleScanResult(result.getText());
        }

        @Override
        public void possibleResultPoints(java.util.List<com.google.zxing.ResultPoint> pts) {}
    };

    private void handleScanResult(String data) {
        switch (phase) {
            case SEND_SCAN_PEER_PUBKEY:
                handleSenderScannedPeerPubkey(data);
                break;
            case RECV_SCAN_PUBKEY:
                handleReceiverScannedPeerPubkey(data);
                break;
            case RECV_SCAN_DATA:
                handleReceiverScannedDataFrame(data);
                break;
            default:
                resumeScanning();
                break;
        }
    }

    // ══════════════════════════════════════════════════
    // SENDER FLOW
    // ══════════════════════════════════════════════════

    /** Step S1: Sender generates keypair and shows da·G QR. */
    private void startSendShowPubkey() {
        if (phase != Phase.IDLE) return;  // prevent double-click
        btnSendMode.setBackgroundTintList(ColorStateList.valueOf(0xFF3F51B5));
        btnReceiveMode.setBackgroundTintList(ColorStateList.valueOf(0xFF78909C));
        hideAll();
        phase = Phase.SEND_SHOW_PUBKEY;
        transferComplete = false;

        new Thread(() -> {
            try {
                ourKeyPair = QrTransferCrypto.generateKeyPair();
                String pkQr = QrTransferCrypto.makePubkeyQr(ourKeyPair.getPublic());
                Bitmap qr = QrTransferCrypto.generateQrBitmap(pkQr, 600);

                runOnUiThread(() -> {
                    tvPubkeyTitle.setText("等待接收方扫描");
                    ivPubkeyQr.setImageBitmap(qr);
                    ivPubkeyQr.setVisibility(View.VISIBLE);
                    tvPubkeyHint.setText("请接收方扫描此二维码");
                    btnPubkeyNext.setText("接收方已扫描，下一步");
                    layoutPubkeyQr.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                e.printStackTrace();
                QrTransferCrypto.zeroKeyPair(ourKeyPair);
                ourKeyPair = null;
                runOnUiThread(() -> {
                    Toast.makeText(this, "生成密钥失败: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    /** Step S1→S2: Receiver has scanned our pubkey, now we scan theirs. */
    private void onPubkeyNextClicked() {
        if (phase == Phase.SEND_SHOW_PUBKEY) {
            pendingAfterPermission = this::startSendScanPeerPubkey;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            } else {
                startSendScanPeerPubkey();
            }
        } else if (phase == Phase.RECV_SHOW_PUBKEY) {
            // Receiver: sender has scanned our pubkey, now scan data
            pendingAfterPermission = this::startRecvScanData;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            } else {
                startRecvScanData();
            }
        }
    }

    /** Step S2: Sender opens camera, scans receiver's db·G QR. */
    private void startSendScanPeerPubkey() {
        hideAll();
        phase = Phase.SEND_SCAN_PEER_PUBKEY;
        transferComplete = false;

        tvScannerStatus.setText("请扫描接收方设备上的二维码");
        tvScannerProgress.setVisibility(View.GONE);
        showScanner();
        resumeScanning();
    }

    /** Step S2→S3: Sender got receiver's pubkey, compute K and encrypt data. */
    private void handleSenderScannedPeerPubkey(String data) {
        if (!QrTransferCrypto.isPubkeyQr(data)) {
            resumeScanning();
            return;
        }

        try {
            peerPublicKey = QrTransferCrypto.parsePubkeyQr(data);
        } catch (Exception e) {
            resumeScanning();
            return;
        }

        tvScannerStatus.setText("✓ 已扫描接收方二维码");
        hideScanner();

        // Compute shared secret + encryption key in background
        new Thread(() -> {
            try {
                char[] pwd = ((MyApp) getApplication()).getMasterPassword();
                if (pwd == null) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "未解锁", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }

                // ECDH: da · Qb
                byte[] sharedSecret = QrTransferCrypto.computeSharedSecret(
                        ourKeyPair, peerPublicKey);
                encryptionKey = QrTransferCrypto.deriveEncryptionKey(sharedSecret);
                Arrays.fill(sharedSecret, (byte) 0);

                // Zero our private key immediately — no longer needed
                QrTransferCrypto.zeroKeyPair(ourKeyPair);
                ourKeyPair = null;

                // Encrypt data with K
                String exportJson = ExportImportHelper.exportToJson(this, dbHelper);
                sendFrames = QrTransferCrypto.createFrames(exportJson, encryptionKey);
                currentFrameIdx = 0;

                runOnUiThread(() -> {
                    phase = Phase.SEND_SHOW_DATA;
                    layoutSendQr.setVisibility(View.VISIBLE);
                    showCurrentFrame();
                });
            } catch (Exception e) {
                e.printStackTrace();
                // Ensure cleanup on failure
                QrTransferCrypto.zeroKeyPair(ourKeyPair);
                ourKeyPair = null;
                QrTransferCrypto.zeroKey(encryptionKey);
                encryptionKey = null;
                runOnUiThread(() -> {
                    Toast.makeText(this, "准备传输失败: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        }).start();
    }

    // ── Data frame display (sender) ──────────────────

    private void showCurrentFrame() {
        if (sendFrames == null || sendFrames.isEmpty()) return;

        QrTransferCrypto.Frame frame = sendFrames.get(currentFrameIdx);
        int total = sendFrames.size();

        try {
            Bitmap qr = QrTransferCrypto.generateQrBitmap(frame.toQrString(), 600);
            ivQrCode.setImageBitmap(qr);
            ivQrCode.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Toast.makeText(this, "生成二维码失败", Toast.LENGTH_SHORT).show();
            return;
        }

        tvFrameProgress.setText((currentFrameIdx + 1) + " / " + total);

        if (currentFrameIdx < total - 1) {
            btnNextFrame.setVisibility(View.VISIBLE);
            btnTransferDone.setVisibility(View.GONE);
            tvFrameHint.setText("请接收方扫描后，点击「下一帧」继续");
        } else {
            btnNextFrame.setVisibility(View.GONE);
            btnTransferDone.setVisibility(View.VISIBLE);
            tvFrameHint.setText("最后一帧 — 接收方扫描后点击完成");
        }
    }

    private void advanceFrame() {
        if (sendFrames == null || currentFrameIdx >= sendFrames.size() - 1) return;
        QrTransferCrypto.zeroFrame(sendFrames.get(currentFrameIdx));
        currentFrameIdx++;
        showCurrentFrame();
    }

    private void finishTransfer() {
        if (sendFrames != null) {
            QrTransferCrypto.zeroAllFrames(
                    sendFrames.toArray(new QrTransferCrypto.Frame[0]));
            sendFrames = null;
        }
        QrTransferCrypto.zeroKey(encryptionKey);
        encryptionKey = null;
        Toast.makeText(this, "传输完成 — 密钥已销毁", Toast.LENGTH_SHORT).show();
        new Handler().postDelayed(this::finish, 800);
    }

    // ══════════════════════════════════════════════════
    // RECEIVER FLOW
    // ══════════════════════════════════════════════════

    /** Step R1: Receiver opens camera immediately, scans sender's da·G QR. */
    private void startRecvScanPubkey() {
        if (phase != Phase.IDLE) return;  // prevent double-click
        btnSendMode.setBackgroundTintList(ColorStateList.valueOf(0xFF78909C));
        btnReceiveMode.setBackgroundTintList(ColorStateList.valueOf(0xFF3F51B5));
        hideAll();
        phase = Phase.RECV_SCAN_PUBKEY;
        transferComplete = false;

        tvScannerStatus.setText("请扫描发送方设备上的二维码");
        tvScannerProgress.setVisibility(View.GONE);
        showScanner();
        resumeScanning();
    }

    /** Step R1→R2: Receiver got sender's pubkey, generate own keypair, show Qb QR. */
    private void handleReceiverScannedPeerPubkey(String data) {
        if (!QrTransferCrypto.isPubkeyQr(data)) {
            resumeScanning();
            return;
        }

        try {
            peerPublicKey = QrTransferCrypto.parsePubkeyQr(data);
        } catch (Exception e) {
            resumeScanning();
            return;
        }

        tvScannerStatus.setText("✓ 已扫描发送方二维码");
        hideScanner();

        new Thread(() -> {
            try {
                // Generate receiver's own keypair
                ourKeyPair = QrTransferCrypto.generateKeyPair();

                // ECDH: db · Qa
                byte[] sharedSecret = QrTransferCrypto.computeSharedSecret(
                        ourKeyPair, peerPublicKey);
                encryptionKey = QrTransferCrypto.deriveEncryptionKey(sharedSecret);
                Arrays.fill(sharedSecret, (byte) 0);

                // Show our public key for sender to scan
                String pkQr = QrTransferCrypto.makePubkeyQr(ourKeyPair.getPublic());
                Bitmap qr = QrTransferCrypto.generateQrBitmap(pkQr, 600);

                // Zero receiver private key — K already derived, QR already generated
                QrTransferCrypto.zeroKeyPair(ourKeyPair);
                ourKeyPair = null;

                runOnUiThread(() -> {
                    phase = Phase.RECV_SHOW_PUBKEY;
                    tvPubkeyTitle.setText("等待发送方扫描");
                    ivPubkeyQr.setImageBitmap(qr);
                    ivPubkeyQr.setVisibility(View.VISIBLE);
                    tvPubkeyHint.setText("请发送方扫描此二维码");
                    btnPubkeyNext.setText("发送方已扫描，开始接收数据");
                    layoutPubkeyQr.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                e.printStackTrace();
                // Ensure cleanup on failure
                QrTransferCrypto.zeroKeyPair(ourKeyPair);
                ourKeyPair = null;
                QrTransferCrypto.zeroKey(encryptionKey);
                encryptionKey = null;
                runOnUiThread(() -> {
                    Toast.makeText(this, "生成密钥失败: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }).start();
    }

    /** Step R2→R3: Receiver opens camera to scan data frames. */
    private void startRecvScanData() {
        hideAll();
        phase = Phase.RECV_SCAN_DATA;
        transferComplete = false;

        receivedFrames = null;
        recvTotal = 0;
        recvCount = 0;
        scanning = false;

        tvScannerStatus.setText("请将摄像头对准发送方屏幕");
        tvScannerProgress.setText("已接收: 0 帧");
        tvScannerProgress.setVisibility(View.VISIBLE);
        showScanner();
        resumeScanning();
    }

    /** Step R3: Process each scanned data frame. */
    private void handleReceiverScannedDataFrame(String qrData) {
        QrTransferCrypto.Frame frame;
        try {
            frame = QrTransferCrypto.Frame.fromQrString(qrData);
        } catch (Exception e) {
            resumeScanning();
            return;
        }

        // First frame: allocate array
        if (receivedFrames == null) {
            recvTotal = frame.total;
            receivedFrames = new QrTransferCrypto.Frame[recvTotal];
            recvCount = 0;
        }

        if (frame.total != recvTotal) {
            tvScannerStatus.setText("⚠ 帧总数不一致");
            handler.postDelayed(this::resumeScanning, 2000);
            return;
        }

        if (receivedFrames[frame.index] != null) {
            // Duplicate frame, keep scanning
            handler.postDelayed(this::resumeScanning, 1000);
            return;
        }

        receivedFrames[frame.index] = frame;
        recvCount++;
        tvScannerProgress.setText("已接收: " + recvCount + " / " + recvTotal + " 帧");

        if (recvCount >= recvTotal) {
            transferComplete = true;
            tvScannerStatus.setText("✓ 全部接收完成，正在解密...");
            scannerView.pause();
            performImport();
        } else {
            tvScannerStatus.setText("✓ 第 " + (frame.index + 1) + " 帧已接收，请发送方切换到下一帧");
            handler.postDelayed(this::resumeScanning, 1500);
        }
    }

    // ── Import ───────────────────────────────────────

    private void performImport() {
        new Thread(() -> {
            try {
                char[] pwd = ((MyApp) getApplication()).getMasterPassword();
                if (pwd == null) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "未解锁", Toast.LENGTH_SHORT).show();
                        finish();
                    });
                    return;
                }

                String exportJson = QrTransferCrypto.assembleFrames(
                        receivedFrames, encryptionKey);

                // Zero crypto material — ourKeyPair already zeroed, encryptionKey not yet
                QrTransferCrypto.zeroKey(encryptionKey);
                encryptionKey = null;
                QrTransferCrypto.zeroAllFrames(receivedFrames);
                receivedFrames = null;

                ExportImportHelper.ImportResult result =
                        ExportImportHelper.importFromJson(this, dbHelper, exportJson, pwd);

                runOnUiThread(() -> {
                    switch (result) {
                        case SUCCESS:
                            Toast.makeText(QrTransferActivity.this,
                                    "传输成功 — 密钥已销毁", Toast.LENGTH_SHORT).show();
                            break;
                        case WRONG_PASSWORD:
                            Toast.makeText(QrTransferActivity.this,
                                    "主密码不匹配 — 两台设备需使用相同主密码",
                                    Toast.LENGTH_LONG).show();
                            break;
                        default:
                            Toast.makeText(QrTransferActivity.this,
                                    "数据损坏，请重试", Toast.LENGTH_SHORT).show();
                            break;
                    }
                    // Always finish after showing result
                    new Handler().postDelayed(QrTransferActivity.this::finish,
                            result == ExportImportHelper.ImportResult.SUCCESS ? 1000 : 2500);
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(QrTransferActivity.this,
                            "解密失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    new Handler().postDelayed(QrTransferActivity.this::finish, 2500);
                });
            }
        }).start();
    }

    // ══════════════════════════════════════════════════
    // Cleanup
    // ══════════════════════════════════════════════════

    private void cancelTransfer() {
        zeroAllSecrets();
        transferComplete = true;
        scanning = false;
        phase = Phase.IDLE;

        scannerView.pause();
        finish();
    }

    private void zeroAllSecrets() {
        if (sendFrames != null) {
            QrTransferCrypto.zeroAllFrames(
                    sendFrames.toArray(new QrTransferCrypto.Frame[0]));
            sendFrames = null;
        }
        QrTransferCrypto.zeroAllFrames(receivedFrames);
        receivedFrames = null;
        QrTransferCrypto.zeroKeyPair(ourKeyPair);
        ourKeyPair = null;
        QrTransferCrypto.zeroKey(encryptionKey);
        encryptionKey = null;
        peerPublicKey = null;
    }
}
