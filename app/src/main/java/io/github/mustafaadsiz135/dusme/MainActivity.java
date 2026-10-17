package io.github.mustafaadsiz135.dusme;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity implements EmergencyFlow.Listener {

    private static final int REQ_PERMS = 42;

    private PreviewView previewView;
    private PoseOverlayView overlay;
    private TextView statusText, infoText, alertTitle, countdownText, heardText;
    private LinearLayout alertPanel, settingsPanel;
    private Button btnOk, btnCallNow, btnReset;
    private EditText phoneEdit, askEdit, answerEdit;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService cameraExecutor;
    private PoseDetector poseDetector;
    private ImageAnalysis analysis;
    private volatile int targetRotation = Surface.ROTATION_0;
    private OrientationEventListener orientationListener;

    private final FallDetector fallDetector = new FallDetector();
    private EmergencyFlow flow;
    private boolean testMode = false;
    private boolean cameraStarted = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        previewView = findViewById(R.id.preview);
        overlay = findViewById(R.id.overlay);
        statusText = findViewById(R.id.statusText);
        infoText = findViewById(R.id.infoText);
        alertPanel = findViewById(R.id.alertPanel);
        alertTitle = findViewById(R.id.alertTitle);
        countdownText = findViewById(R.id.countdownText);
        heardText = findViewById(R.id.heardText);
        btnOk = findViewById(R.id.btnOk);
        btnCallNow = findViewById(R.id.btnCallNow);
        btnReset = findViewById(R.id.btnReset);
        settingsPanel = findViewById(R.id.settingsPanel);
        phoneEdit = findViewById(R.id.phoneEdit);
        askEdit = findViewById(R.id.askEdit);
        answerEdit = findViewById(R.id.answerEdit);

        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);

        flow = new EmergencyFlow(this, this);
        cameraExecutor = Executors.newSingleThreadExecutor();
        poseDetector = PoseDetection.getClient(new PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build());

        loadSettingsIntoForm();

        btnOk.setOnClickListener(v -> flow.userSaysOk());
        btnCallNow.setOnClickListener(v -> flow.callNow());
        btnReset.setOnClickListener(v -> flow.reset());
        findViewById(R.id.btnSettings).setOnClickListener(v -> toggleSettings());
        findViewById(R.id.btnSave).setOnClickListener(v -> saveSettings());
        findViewById(R.id.btnTest).setOnClickListener(v -> {
            if (flow.getState() == EmergencyFlow.State.IDLE) {
                testMode = true;
                flow.start();
            }
        });

        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int rot;
                if (o >= 45 && o < 135) rot = Surface.ROTATION_270;
                else if (o >= 135 && o < 225) rot = Surface.ROTATION_180;
                else if (o >= 225 && o < 315) rot = Surface.ROTATION_90;
                else rot = Surface.ROTATION_0;
                if (rot != targetRotation) {
                    targetRotation = rot;
                    if (analysis != null) analysis.setTargetRotation(rot);
                }
            }
        };

        if (AppPrefs.getPhone(this).isEmpty()) {
            settingsPanel.setVisibility(View.VISIBLE);
        }
        requestNeededPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        orientationListener.disable();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        flow.release();
        poseDetector.close();
        cameraExecutor.shutdown();
    }

    // ---------------- Izinler ----------------

    private void requestNeededPermissions() {
        String[] all = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.CALL_PHONE};
        List<String> missing = new ArrayList<>();
        for (String p : all) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            startCamera();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;
        if (has(Manifest.permission.CAMERA)) {
            startCamera();
        } else {
            statusText.setText("Kamera izni olmadan uygulama çalışamaz.");
        }
        StringBuilder warn = new StringBuilder();
        if (!has(Manifest.permission.RECORD_AUDIO)) warn.append("Mikrofon izni yok: sesli yanıt alınamaz. ");
        if (!has(Manifest.permission.CALL_PHONE)) warn.append("Telefon izni yok: otomatik arama yapılamaz.");
        if (warn.length() > 0) Toast.makeText(this, warn.toString(), Toast.LENGTH_LONG).show();
    }

    private boolean has(String perm) {
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------- Kamera + yapay zeka ----------------

    private void startCamera() {
        if (cameraStarted) return;
        cameraStarted = true;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetRotation(targetRotation)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyze);

                provider.unbindAll();
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                statusText.setText(idleStatus());
            } catch (Exception e) {
                cameraStarted = false;
                statusText.setText("Kamera açılamadı: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @ExperimentalGetImage
    private void analyze(ImageProxy proxy) {
        Image media = proxy.getImage();
        if (media == null) { proxy.close(); return; }
        final int rotation = proxy.getImageInfo().getRotationDegrees();
        final boolean swap = rotation == 90 || rotation == 270;
        final int w = swap ? proxy.getHeight() : proxy.getWidth();
        final int h = swap ? proxy.getWidth() : proxy.getHeight();
        final boolean portrait = targetRotation == Surface.ROTATION_0;

        InputImage input = InputImage.fromMediaImage(media, rotation);
        poseDetector.process(input)
                .addOnSuccessListener(pose -> onPose(pose, w, h, portrait)) // ana is parcaciginda calisir
                .addOnCompleteListener(t -> proxy.close());
    }

    private void onPose(Pose pose, int w, int h, boolean portrait) {
        long now = SystemClock.elapsedRealtime();
        FallDetector.Posture p = fallDetector.classify(pose, w, h);
        overlay.setPose(portrait ? pose : null, w, h);

        EmergencyFlow.State st = flow.getState();
        boolean fall = fallDetector.onFrame(p, now);
        if (st == EmergencyFlow.State.IDLE) {
            if (fall) flow.start();
        } else if (!testMode && fallDetector.isStandingUp(now)) {
            flow.onPersonStoodUp();
        }

        String postur;
        switch (p) {
            case UPRIGHT: postur = "DİK"; break;
            case LYING: postur = "YATIYOR"; break;
            case OTHER: postur = "eğik / oturuyor"; break;
            default: postur = "kişi görünmüyor"; break;
        }
        String info = "Duruş: " + postur;
        if (!Float.isNaN(fallDetector.lastAngle)) {
            info += String.format(Locale.getDefault(), "   •   Gövde açısı: %.0f°", fallDetector.lastAngle);
        }
        if (!portrait) info += "   •   (yatay mod)";
        infoText.setText(info);
    }

    // ---------------- Acil durum akisi geri bildirimleri ----------------

    @Override
    public void onStateChanged(EmergencyFlow.State state, String message) {
        switch (state) {
            case IDLE:
                alertPanel.setVisibility(View.GONE);
                fallDetector.reset();
                testMode = false;
                overlay.setColor(Color.parseColor("#64B5F6"));
                statusText.setText(message);
                handler.removeCallbacks(restoreStatus);
                handler.postDelayed(restoreStatus, 5000);
                break;
            case WAITING:
            case LISTENING:
                alertPanel.setVisibility(View.VISIBLE);
                alertTitle.setText(state == EmergencyFlow.State.WAITING ? "DÜŞME ALGILANDI" : "İYİ MİSİNİZ?");
                heardText.setText(testMode ? "(Test modu)\n" + message : message);
                btnOk.setVisibility(View.VISIBLE);
                btnCallNow.setVisibility(View.VISIBLE);
                btnReset.setVisibility(View.GONE);
                overlay.setColor(Color.RED);
                statusText.setText(message);
                break;
            case CALLED:
                alertPanel.setVisibility(View.VISIBLE);
                alertTitle.setText("ACİL ARAMA");
                countdownText.setText("📞");
                heardText.setText(message);
                btnOk.setVisibility(View.GONE);
                btnCallNow.setVisibility(View.GONE);
                btnReset.setVisibility(View.VISIBLE);
                statusText.setText(message);
                break;
        }
    }

    @Override
    public void onCountdown(String text) {
        countdownText.setText(text);
    }

    @Override
    public void onHeard(String text) {
        heardText.setText(text);
    }

    private final Runnable restoreStatus = () -> {
        if (flow.getState() == EmergencyFlow.State.IDLE) statusText.setText(idleStatus());
    };

    private String idleStatus() {
        String phone = AppPrefs.getPhone(this);
        return phone.isEmpty()
                ? "İzleniyor… ⚠ Acil numara ayarlı değil (AYARLAR)"
                : "İzleniyor… Acil numara: " + phone;
    }

    // ---------------- Ayarlar ----------------

    private void toggleSettings() {
        boolean show = settingsPanel.getVisibility() != View.VISIBLE;
        settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) loadSettingsIntoForm(); else hideKeyboard();
    }

    private void loadSettingsIntoForm() {
        phoneEdit.setText(AppPrefs.getPhone(this));
        askEdit.setText(String.valueOf(AppPrefs.getAskSec(this)));
        answerEdit.setText(String.valueOf(AppPrefs.getAnswerSec(this)));
    }

    private void saveSettings() {
        String phone = AppPrefs.clean(phoneEdit.getText().toString());
        if (phone.length() < 3) {
            phoneEdit.setError("Geçerli bir numara girin");
            return;
        }
        AppPrefs.setPhone(this, phone);
        AppPrefs.setTimes(this, parse(askEdit, AppPrefs.DEFAULT_ASK_SEC), parse(answerEdit, AppPrefs.DEFAULT_ANSWER_SEC));
        loadSettingsIntoForm();
        settingsPanel.setVisibility(View.GONE);
        hideKeyboard();
        if (flow.getState() == EmergencyFlow.State.IDLE) statusText.setText(idleStatus());
        Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show();
    }

    private static int parse(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception ex) { return def; }
    }

    private void hideKeyboard() {
        View v = getCurrentFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (v != null && imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }
}
