// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.speechtest;


import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.icu.text.SimpleDateFormat;
import android.icu.text.Transliterator;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.util.Arrays;

import org.jetbrains.annotations.Nullable;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.media.audiofx.NoiseSuppressor;
import com.konovalov.vad.webrtc.Vad;
import com.konovalov.vad.webrtc.VadWebRTC;
import com.konovalov.vad.webrtc.config.FrameSize;
import com.konovalov.vad.webrtc.config.Mode;
import com.konovalov.vad.webrtc.config.SampleRate;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class VoskActivity extends Activity implements
        RecognitionListener {

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_FILE = 3;
    static private final int STATE_MIC = 4;
    private static final int msg_error = 7;
    private static final int msg_success = 8;
    private static final int Maxnum = 3;
    private static final int SILENCE_THRESHOLD = 250;
    private static final int MIN_UTTER_MS = 100;     // 最短有效語段，避免喘氣誤觸
    private static final int MAX_UTTER_MS = 1200;    // 安全上限

    private boolean inUtterance = false;             // 是否在一段語音中
    private int tailSilenceMs = 0;               // 語段尾巴的靜音累計
    private long utterStartMs = 0;               // 語段起點時間

    private static final String TAG = "VoskActivity";
    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 320;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private int speed = 1;
    private long lastSpeedUpTime = 0;
    private final Trie keywordsTrie = new Trie();
    private ExecutorService executorService = Executors.newFixedThreadPool(10);
    private AtomicReference<LatencyRecord> latencyRecordRef = new AtomicReference<>(null);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    //private final ScheduledExecutorService stopScheduler = Executors.newSingleThreadScheduledExecutor();
    // 儲存選項
    private static final String PREFS = "rec_prefs";
    private static final String KEY_SAVE_AUDIO = "save_audio";
    private volatile boolean saveAudio = true;


    private File outDir;
    private WavWriter wavWriter;
    private TextView badgeSaving;
    private android.view.animation.AlphaAnimation blinkAnim;

    private ScheduledFuture<?> repeatTask;
    private ScheduledFuture<?> stopTask;
    private byte[] lastPacket = null;
    private static final long REPEAT_INTERVAL = 3000;
    private boolean isModelLoaded = false;

    private TcpClient tcpClient;
    private AppDatabase db;
    private VehicleMode currentMode = VehicleMode.CAR;
    private @Nullable String lastSentCommand = null;
    private long lastSentAt = 0L;
    private long sessionStartTime = 0L;
    private long sessionEndTime = 0L;
    private static final long MIN_INTERVAL_MS = 600;
    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    private Map<String, Integer> keywordMap = new HashMap<>();
    private static final Transliterator SIMPLIFIED_TO_TRADITIONAL =
            Transliterator.getInstance("Simplified-Traditional");
    private Model model;
    private List<String> keywordCache = new ArrayList<>();

    private TextView resultView;
    private TextView tvpacket;
    private TextView tvSpeed;
    private TextView successText;
    private int frameCnt = 0;
    private int stopCount = 0;
    private int confSuccessCount = 0;
    private TextView tvKeywords;
    private FrameLayout loadingOverlay;
    private TextView progessText;
    private ProgressBar progessBar;
    private VadWebRTC vad;
    private SpeechService speechService;
    private Recognizer rec;
    private volatile boolean emergencyHold = false;

    private boolean firstCommandSent = false;
    private AudioRecord recorder;


    private SpeechStreamService speechStreamService;
    private Thread audioWriterThread;
    private volatile boolean wasSpeech = false;
    private boolean isRecording = false;
    private Set<String> validCommands = new HashSet<>();
    private String lastExecutedCommand = "";
    private NoiseSuppressor ns;

    private boolean noiseSuppressionEnabled = true;

    static class LatencyRecord {
        final long T1_speechStartNano;
        volatile long T2_partialOkNano = 0;
        String partialCommand = "";
        volatile boolean isVoiceFrame = false;

        LatencyRecord(long speechStartNano) {
            this.T1_speechStartNano = speechStartNano;
        }
    }

    private LinearLayout mainLayout;

    private enum RecState {STOPPED, RUNNING, PAUSED}

    private volatile RecState recState = RecState.STOPPED;
    private long lastMicToggleMs = 0;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        // Setup layout
        ToggleButton toggleMode = findViewById(R.id.toggleMode);
        loadingOverlay = findViewById(R.id.loadingOverlay);
        progessBar = findViewById(R.id.progessBar);
        progessText = findViewById(R.id.progessText);
        mainLayout = findViewById(R.id.mainLayout);
        resultView = findViewById(R.id.result_text);
        tvpacket = findViewById(R.id.tv_packet);
        successText = findViewById(R.id.successText);
        tvKeywords = findViewById(R.id.tvKeywords);
        tvSpeed = findViewById(R.id.tvSpeed);
        Switch nsSwitch = findViewById(R.id.s_switch);
        badgeSaving = findViewById(R.id.badgeSaving);
        nsSwitch.setChecked(noiseSuppressionEnabled);
        setUiState(STATE_START);
        ToggleButton pauseButton = findViewById(R.id.pause);
        pauseButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!buttonView.isPressed()) return;
            recState = isChecked ? RecState.PAUSED : RecState.RUNNING;
            wasSpeech = false;
            if (isChecked) {
                sendRawPacket(new byte[]{(byte) 0xA5, 0x50, 0x00, 0x00, 0x00, (byte) 0xFA});
            }
        });
        db = AppDatabase.getDatabase(this);
        if (db != null) {
            Log.i("VoskActivity", "數據庫已成功初始化");
        } else {
            Log.e("VoskActivity", "數據庫初始化失败");
        }
        loadkeyword();
        Log.i("VoskActivity", "數據庫初始化完成");
        toggleMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            currentMode = isChecked ? VehicleMode.UAV : VehicleMode.CAR;
            // stopSpeechRecognition();
            loadkeyword();       // 重新載入當前模式的指令 Trie
            //restartRecognizer(); // 替換語音詞庫（不同 Grammar）
            Toast.makeText(this,
                    "已切換為 " + (isChecked ? "UAV" : "CAR"),
                    Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> {
            long now = SystemClock.uptimeMillis();
            if (now - lastMicToggleMs < 500) return; // 防連點
            lastMicToggleMs = now;
            toggleMicrophoneRecognition();
        });
        outDir = new File(getExternalFilesDir(null), "VoskTest");
        if (!outDir.exists()) outDir.mkdirs();
        saveAudio = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SAVE_AUDIO, true);
        nsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            noiseSuppressionEnabled = isChecked;
            // 若此時已經在錄音，立刻套用/關閉
            if (recState == RecState.RUNNING && recorder != null) {
                if (isChecked) {
                    applyNoiseSuppressor();
                } else {
                    releaseNoiseSuppressor();
                }
            }
        });
        LibVosk.setLogLevel(LogLevel.INFO);
        // Check if user has given permission to record audio, init the model after permission is granted
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            downloadModel(progessBar, progessText, loadingOverlay, mainLayout);
        }
        tcpClient = new TcpClient("10.61.73.180", 8080, Executors.newSingleThreadExecutor());
        tcpClient.initializeTcpConnection();
        initializeVAD();
    }

    private void downloadModel(ProgressBar progessBar, TextView progessText, FrameLayout loadingOverlay, LinearLayout mainLayout) {
        progessBar.setProgress(0);
        loadingOverlay.setVisibility(View.VISIBLE); // 顯示進度條

        new Thread(() -> {
            try {
                for (int i = 0; i <= 100; i += 10) { // 每次增加 10%
                    int progressValue = i;
                    runOnUiThread(() -> {
                        progessBar.setProgress(progressValue);
                        progessText.setText("Downloading AI model... " + progressValue + "%");
                    });
                    Thread.sleep(800); // 模擬下載
                }

                StorageService.unpack(this, "vosk-model-cn-kaldi-multicn-0.15", "model",
                        (model) -> {
                            this.model = model;
                            isModelLoaded = true;
                            runOnUiThread(() -> {
                                loadingOverlay.setVisibility(View.GONE); // 隱藏下載畫面
                                mainLayout.setVisibility(View.VISIBLE); // 顯示主畫面
                                setUiState(STATE_READY);
                                Log.d("VoskActivity", "模型載入成功");
                            });
                        },
                        (exception) -> runOnUiThread(() -> {
                            setErrorState("Failed to unpack the model: " + exception.getMessage());
                            loadingOverlay.setVisibility(View.GONE);
                        })

                );
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();


    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.option_menu, menu);
        menu.findItem(R.id.action_toggle_save).setChecked(saveAudio);
        menu.findItem(R.id.action_show_path);
        menu.findItem(R.id.action_list_recordings);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull android.view.MenuItem item) {
        if (item.getItemId() == R.id.action_toggle_save) {
            boolean now = !item.isChecked();
            item.setChecked(now);
            saveAudio = now;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SAVE_AUDIO, now).apply();
            Toast.makeText(this, now ? "將儲存辨識錄音" : "僅辨識，不儲存檔案", Toast.LENGTH_SHORT).show();
            updateSavingBadge();
            return true;
        } else if (item.getItemId() == R.id.action_show_path) {
            showPathDialog();
            return true;
        }else if (item.getItemId() == R.id.action_list_recordings) {
            startActivity(new android.content.Intent(this, RecordingListActivity.class));
            return true;
        }else if (item.getItemId() == R.id.action_publish_latest) {
            publishLatestToMusic();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
    private List<File> listWavFiles() {
        if (outDir == null) return Collections.emptyList();
        File[] arr = outDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".wav"));
        if (arr == null) return Collections.emptyList();
        List<File> files = new ArrayList<>(Arrays.asList(arr));
        files.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return files;
    }

    private void showRecordingPickerDialog() {
        List<File> files = listWavFiles();
        if (files.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("音檔清單")
                    .setMessage("目前沒有 .wav 檔案")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String[] items = new String[files.size()];
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            String meta = sdf.format(new Date(f.lastModified()));
            String size = android.text.format.Formatter.formatShortFileSize(this, f.length());
            items[i] = f.getName() + "  (" + size + ", " + meta + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("選擇音檔")
                .setItems(items, (d, which) -> showRecordingActions(files.get(which)))
                .setNegativeButton("關閉", null)
                .show();
    }

    private void showRecordingActions(File f) {
        String[] actions = {"開啟", "分享"};
        new AlertDialog.Builder(this)
                .setTitle(f.getName())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) openWithExternalApp(f);
                    else shareAudio(f);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // 使用 FileProvider 開啟
    private void openWithExternalApp(File f) {
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", f);
        android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_VIEW);
        it.setDataAndType(uri, "audio/wav");
        it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(it);
        } catch (Exception e) {
            Toast.makeText(this, "沒有可開啟音檔的 App", Toast.LENGTH_SHORT).show();
        }
    }

    // 分享
    private void shareAudio(File f) {
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", f);
        android.content.Intent it = new android.content.Intent(android.content.Intent.ACTION_SEND);
        it.setType("audio/wav");
        it.putExtra(android.content.Intent.EXTRA_STREAM, uri);
        it.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(android.content.Intent.createChooser(it, "分享音檔"));
    }
    private void publishLatestToMusic() {
        List<File> files = listWavFiles();
        if (files.isEmpty()) {
            Toast.makeText(this, "沒有可發佈的檔案", Toast.LENGTH_SHORT).show();
            return;
        }
        File latest = files.get(0);
        android.net.Uri uri = publishToMusic(latest, "VoskTest");
        Toast.makeText(this, uri != null ? "已發佈: " + latest.getName() : "發佈失敗", Toast.LENGTH_SHORT).show();
    }

    private android.net.Uri publishToMusic(File wavFile, String subdir) {
        try {
            android.content.ContentValues v = new android.content.ContentValues();
            v.put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, wavFile.getName());
            v.put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                v.put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_MUSIC + "/" + subdir);
            }
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;

            try (java.io.InputStream in = new java.io.FileInputStream(wavFile);
                 java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return uri;
        } catch (Exception e) {
            Log.w(TAG, "Publish to MediaStore failed: " + wavFile, e);
            return null;
        }
    }


    private void toggleMicrophoneRecognition() {
        if (isRecording) {
            stopSpeechRecognition("mic button");  // 停止語音識別
            setUiState(STATE_READY);  // 更新UI狀態
            lastExecutedCommand = "";
        } else {
            wasSpeech = false;
            startSpeechRecognition();  // 開始語音識別
            setUiState(STATE_MIC);  // 更新UI狀態
        }
    }

    private void updateSavingBadge() {
        boolean isRunning = (recState == RecState.RUNNING) && (recorder != null);
        boolean shouldShow = saveAudio && isRunning;

        if (shouldShow) {
            badgeSaving.setVisibility(View.VISIBLE);


            if (blinkAnim == null) {
                blinkAnim = new android.view.animation.AlphaAnimation(1.0f, 0.35f);
                blinkAnim.setDuration(1000);
                blinkAnim.setRepeatMode(android.view.animation.Animation.REVERSE);
                blinkAnim.setRepeatCount(android.view.animation.Animation.INFINITE);
            }
            if (badgeSaving.getAnimation() == null) {
                badgeSaving.startAnimation(blinkAnim);
            }
        } else {
            badgeSaving.clearAnimation();
            badgeSaving.setVisibility(View.GONE);
        }
    }
    private void showPathDialog() {
        if (outDir == null){
            Toast.makeText(this,"尚未建立輸出資料夾",Toast.LENGTH_SHORT).show();
            return;
        }
        String path = outDir.getAbsolutePath();
        new AlertDialog.Builder(this)
                .setTitle("儲存路徑")
                .setMessage(path)
                .setPositiveButton("複製", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("path", path));
                    Toast.makeText(this, "已複製路徑", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("關閉", null)
                .show();
    }


    // 初始化 WebRTC VAD
    private void initializeVAD() {
        vad = Vad.builder()
                .setSampleRate(SampleRate.SAMPLE_RATE_16K)  // 設定取樣率為 16 kHz
                .setFrameSize(FrameSize.FRAME_SIZE_320)    // 設定每幀大小為 320（約 20ms）
                .setMode(Mode.NORMAL)                      // 設定為正常模式
                .setSilenceDurationMs(200)                 // 設定靜音時間為 150 毫秒
                .setSpeechDurationMs(50)                   // 設定語音檢測時間為 50 毫秒
                .build();
    }
    // 初始化SpeechService


    private void loadkeyword() {
        executorService.execute(() -> {
            if (db == null) {
                Log.e("VoskActivity", "Database is not initialized");
                return;
            }
            InstructionDao instructionDao = db.instructionDao();


            keywordCache = instructionDao.getAllKeywords(currentMode.name()); //取得關鍵字
            Set<String> newValidCommands = new HashSet<>();
            if (keywordCache != null) {
                newValidCommands.addAll(keywordCache);
            }
            this.validCommands = newValidCommands;
            Log.d("Cache", "關鍵字已儲存：" + keywordCache.toString());
            keywordsTrie.clear();
            if (keywordCache != null && !keywordCache.isEmpty()) {
                for (String keyword : keywordCache) {
                    String packet = instructionDao.findPacketByKeyword(keyword, currentMode.name());
                    if (packet != null) {
                        keywordsTrie.insert(keyword, packet);
                    } else {
                        Log.w("VoskActivity", "未找到關鍵字對應的數值：" + keyword);
                    }
                }
                Log.d("VoskActivity", "Trie 已成功加載，關鍵字數量: " + keywordCache.size());
            } else {
                Log.w("VoskActivity", "keywordCache is empty");
            }
        });
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> downloadModel(progessBar, progessText, loadingOverlay, mainLayout));
            } else {
                showPermissionDeniedDialog();
            }
        }
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
                .setTitle("錄音權限被拒絕")
                .setMessage("此應用程式需要錄音權限來運行，請前往設定開啟權限。")
                .setPositiveButton("重新請求", (dialog, which) -> {
                    ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
                })
                .setNegativeButton("退出", (dialog, which) -> {
                    finish();
                })
                .setCancelable(false)
                .show();
    }

    @Override
    public void onDestroy() {
        stopSpeechRecognition("activity destroy");
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
        if (tcpClient != null) {
            tcpClient.closeTcpConnection();
            tcpClient = null;
        }
        super.onDestroy();
    }

    private String extractHit(String sentence) {
        String winner = null;
        for (String kw : validCommands) {
            if (sentence.contains(kw)) {
                if (winner == null || kw.length() > winner.length()) {
                    winner = kw;
                }
            }
        }
        return winner;
    }
    private double handleWordConfidences(JSONObject json) {
        try {
            org.json.JSONArray words = json.optJSONArray("result");
            if (words == null || words.length() == 0) {
                Log.i("VOSK_CONF", "No word-level result in this hypothesis.");
                return -1.0; // 表示這次沒有 word conf（例如空字串）
            }

            double sum = 0.0;
            double min = 1.0;

            for (int i = 0; i < words.length(); i++) {
                JSONObject w = words.getJSONObject(i);

                String word = w.optString("word", "");
                double conf = w.optDouble("conf", 0.0);

                sum += conf;
                if (conf < min) min = conf;

                Log.i("VOSK_CONF", "word = " + word + " | conf = " + conf);
            }

            double avg = sum / words.length();
            Log.i("VOSK_CONF", "avgConf = " + avg + " | minConf = " + min);
            confSuccessCount++;
            if (confSuccessCount == 50) {
                Log.i("VOSK_CONF",
                        "詞語信心值已成功計算達 " + confSuccessCount + " 次");
            confSuccessCount = 0;
            }
            return avg;
        } catch (Exception e) {
            Log.w("VOSK_CONF", "Failed to parse word confidences", e);
            return -1.0;
        }
    }

    @Override
    public void onResult(String hypothesis) {
        Log.d("VoskActivity", "onResult: " + hypothesis);
        if (recState == RecState.PAUSED) return;
        LatencyRecord record = latencyRecordRef.get();
        wasSpeech = false;
        if (record == null) {
            Log.w(TAG, "onResult 觸發，但沒有找到對應的語音事件起點 (可能是語音過短或VAD觸發間隙)，忽略。");
            return;
        }
        try {
            JSONObject json = new JSONObject(hypothesis);
            handleWordConfidences(json);
            String finalText = json.optString("text", "");
            if (TextUtils.isEmpty(finalText)) return;
            String collapsed = collapseDuplicates(finalText); //去除重複詞
            String traditionalPartial = SIMPLIFIED_TO_TRADITIONAL.transliterate(collapsed)
                    .replaceAll("\\s+", ""); // 簡繁轉換並移除所有空格
            if (traditionalPartial.isEmpty()) return;
            String hit = extractHit(traditionalPartial);
            if (hit == null) return;
            if (record != null) {
                if (!hit.equals(record.partialCommand)) {
                    record.partialCommand = hit; //
                    record.T2_partialOkNano = System.nanoTime();
                }
            }
            if (record != null) {
                switch (hit) {
                    case "加速":
                        voiceSpeedUp(record);
                        break;
                    case "減速":
                        voiceSpeedDown(record);
                        break;
                    default:
                        generatePacket(Collections.singletonList(hit), record);
                }
            }
            runOnUiThread(() -> {
                updateResultView(traditionalPartial); // 更新主顯示區
                updateKeywordView(Collections.singletonList(hit));  // 更新匹配關鍵字區域
            });

        } catch (JSONException e) {
            Log.e("VoskActivity", "Error parsing hypothesis JSON", e);
        }

    }

    @Override
    public void onFinalResult(String hypothesis) {
        Log.d("VoskActivity", "Final Result: " + hypothesis);
        latencyRecordRef.set(null); // 清理
        lastExecutedCommand = "";
    }

    private void generatePacket(List<String> keywords, LatencyRecord record) {
        executorService.execute(() -> {

            if (!tcpClient.isTcpConnected() || !isModelLoaded) return;


            if (currentMode == VehicleMode.CAR&&!firstCommandSent) {
                firstCommandSent = true;
                sendRawPacket(new byte[]{(byte) 0xA5, (byte) 0x81, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFA});
                sendRawPacket(new byte[]{(byte) 0xA5, (byte) 0x80, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFA});
            }


            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(0xA5);
            for (String kw : keywords) {
                String core = keywordsTrie.search(kw);
                if (core != null) {
                    for (String part : core.split(" "))
                        buf.write(Integer.parseInt(part, 16) & 0xFF);
                }
            }
            buf.write(0xFA);
            byte[] packet = buf.toByteArray();
            lastPacket = packet;


            String commandForDedup = keywords.isEmpty() ? null : keywords.get(0);

            switch (currentMode) {
                case CAR: {
                    // 單次送
                    sendPacketOnce(packet, commandForDedup, record);

                    // 轉彎補一次（1 秒後），避免小車漏包（可依需要保留）
                    boolean isTurn = keywords.contains("左轉") || keywords.contains("右轉");
                    if (isTurn) {
                        scheduler.schedule(() -> sendPacketOnce(packet, commandForDedup, null),
                                1000, java.util.concurrent.TimeUnit.MILLISECONDS);
                    }
                    break;
                }
                case UAV: {
                    // 乾淨模式：只送一次，不補包、不初始化
                    sendPacketOnce(packet, commandForDedup, record);
                    break;
                }
            }
        });
    }
    private void sendPacketOnce(byte[] packet, @Nullable String command, @Nullable LatencyRecord record) {
        long now = android.os.SystemClock.elapsedRealtime();


       // if (command != null && command.equals(lastSentCommand)) return;


        if (now - lastSentAt < MIN_INTERVAL_MS) return;

        sendRawPacket(packet, record);

        lastSentCommand = command;
        lastSentAt = now;
    }


    private void sendRawPacket(byte[] packet, LatencyRecord record) {
        long T3_packetSendNano = System.nanoTime();


        if (record.T1_speechStartNano > 0 && record.T2_partialOkNano > 0) {
            double tMicToOk = (record.T2_partialOkNano - record.T1_speechStartNano) / 1_000_000.0;
            double tOkToSend = (T3_packetSendNano - record.T2_partialOkNano) / 1_000_000.0;
            double tEndToEnd = (T3_packetSendNano - record.T1_speechStartNano) / 1_000_000.0;

            Log.i("LATENCY_DIAGNOSIS",
                    String.format("指令[%s] | Mic➜OK: %.2f ms | OK➜Send: %.2f ms | 端到端總計: %.2f ms",
                            record.partialCommand, tMicToOk, tOkToSend, tEndToEnd));
        }
        sendRawPacket(packet);
    }

    private void sendRawPacket(byte[] packet) {
        if (recState == RecState.PAUSED) {
            runOnUiThread(() -> tvpacket.setText("(BLOCKED) " + HexUtils.byteToHexString(packet)));
            return;
        }
        tcpClient.sendPacket(packet, System.currentTimeMillis(), System.currentTimeMillis());
        runOnUiThread(() -> tvpacket.setText(HexUtils.byteToHexString(packet)));
    }


    private void voiceSpeedUp(LatencyRecord record) {
        long now = System.currentTimeMillis();
        if (now - lastSpeedUpTime < 1000) return;
        lastSpeedUpTime = now;
        executorService.execute(() -> {
            if (!tcpClient.isTcpConnected() || !isModelLoaded) {
                Log.e(TAG, "TCP 未就绪或模型未加载，无法发加速包");
                return;
            }

            if (speed < 10) speed++;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(0xA5);
            buf.write(0x70);
            try {
                int fast = Integer.parseInt(HexUtils.Conversion(speed), 16);
                buf.write(fast & 0xFF);       // speed
            } catch (Exception e) {
                buf.write(speed & 0xFF);
            }
            buf.write(0xFF);
            buf.write(0xFF);
            buf.write(0xFA);

            byte[] packet = buf.toByteArray();

            sendRawPacket(packet, record);
            runOnUiThread(() -> {
                tvpacket.setText(HexUtils.byteToHexString(packet));
                tvSpeed.setText("速度 : " + speed);
            });
        });
    }

    private void voiceSpeedDown(LatencyRecord record) {
        long now = System.currentTimeMillis();
        if (now - lastSpeedUpTime < 1000) return;
        lastSpeedUpTime = now;
        executorService.execute(() -> {
            if (!tcpClient.isTcpConnected() || !isModelLoaded) return;

            if (speed > 1) speed--;
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            buf.write(0xA5);
            buf.write(0x70);
            int slow = Integer.parseInt(HexUtils.Conversion(speed), 16);
            buf.write(slow & 0xFF);
            buf.write(0xFF);
            buf.write(0xFF);
            buf.write(0xFA);

            byte[] packet = buf.toByteArray();
            sendRawPacket(packet, record);
            runOnUiThread(() -> {
                tvpacket.setText(HexUtils.byteToHexString(packet));
                tvSpeed.setText("速度 : " + speed);
            });
        });
    }

    private void applyNoiseSuppressor() {
        if (!noiseSuppressionEnabled) return;
        if (!NoiseSuppressor.isAvailable() || recorder == null) return;
        try {
            if (ns != null) {
                try {
                    ns.release();
                } catch (Throwable e) {
                }
                ns = null;
            }
            ns = NoiseSuppressor.create(recorder.getAudioSessionId());
            if (ns != null) ns.setEnabled(true);
            Log.d(TAG, "降躁已啟用");
        } catch (Throwable e) {
            Log.w(TAG, "啟用降躁失敗", e);
        }
    }

    private void releaseNoiseSuppressor() {
        if (ns != null) {
            try {
                ns.setEnabled(false);
            } catch (Throwable ignore) {
            }
            try {
                ns.release();
            } catch (Throwable ignore) {
            }
            ns = null;
            Log.d(TAG, "降躁已關閉並釋放");
        }
    }


    private void updateKeywordView(List<String> matchedKeywords) {
        if (matchedKeywords == null || matchedKeywords.isEmpty()) {
            tvKeywords.setText("未匹配到關鍵字。");
            return;
        }

        StringBuilder keywordText = new StringBuilder("匹配的關鍵字：\n");
        for (String keyword : matchedKeywords) {
            keywordText.append(keyword).append("\n");
        }
        keywordText.append("時間：")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        tvKeywords.setText(keywordText.toString());
    }

    private void updateResultView(String cleanTraditionalText) {
        resultView.setText("即時結果：\n" + cleanTraditionalText);
    }


    @Override
    public void onPartialResult(String hypothesis) {

        LatencyRecord record = latencyRecordRef.get();

        if (record != null && record.T2_partialOkNano == 0) {
            try {
                JSONObject json = new JSONObject(hypothesis);
                String partialText = json.optString("text", json.optString("partial", ""));

                if (!TextUtils.isEmpty(partialText)) {
                    String traditionalPartial = SIMPLIFIED_TO_TRADITIONAL.transliterate(partialText)
                            .replaceAll("\\s+", "");

                    if (validCommands.contains(traditionalPartial)) {
                        record.T2_partialOkNano = System.nanoTime();
                        record.partialCommand = traditionalPartial;
                        Log.d("LATENCY_TRACK", "偵察到有效指令 (T2): " + traditionalPartial);
                    }
                }
            } catch (JSONException e) {
            }
        }


        runOnUiThread(() -> {
            String cleanTraditionalText;
            try {
                JSONObject json = new JSONObject(hypothesis);
                String textToShow = json.optString("partial", "");
                cleanTraditionalText = SIMPLIFIED_TO_TRADITIONAL.transliterate(textToShow);
            } catch (JSONException e) {
                Log.w(TAG, "onPartialResult UI update: Failed to parse JSON, showing empty. JSON: " + hypothesis);
                cleanTraditionalText = "辨識中...";
            }
            updateResultView(cleanTraditionalText);
        });
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
        latencyRecordRef.set(null);
    }

    private void setUiState(int state) {
        ToggleButton pauseButton = findViewById(R.id.pause);

        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                pauseButton.setEnabled(false);
                pauseButton.setChecked(false);
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                pauseButton.setEnabled(true);
                pauseButton.setChecked(false);
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.recognize_file);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                pauseButton.setEnabled(true);
                pauseButton.setChecked(false);

                break;
            case STATE_FILE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.stop_file);
                resultView.setText(getString(R.string.starting));
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.recognize_file).setEnabled(true);
                pauseButton.setEnabled((false));
                pauseButton.setChecked(false);
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(true);
                pauseButton.setEnabled((true));
                pauseButton.setChecked(false);
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        resultView.setText(message);
        ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
        findViewById(R.id.recognize_file).setEnabled(false);
        findViewById(R.id.recognize_mic).setEnabled(false);
    }

    private void recognizeFile() {
        if (speechStreamService != null) {
            setUiState(STATE_DONE);
            speechStreamService.stop();
            speechStreamService = null;
        } else {
            setUiState(STATE_FILE);
            try {
                Recognizer rec = new Recognizer(model, 16000.f, "[\"左轉\", \"右轉\", \"前進\", \"後退\", \"停\"]");

                InputStream ais = getAssets().open(
                        "10001-90210-01803.wav");
                if (ais.skip(44) != 44) throw new IOException("File too short");

                speechStreamService = new SpeechStreamService(rec, ais, 16000);
                speechStreamService.start(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }


    private static final String[] CAR_CMDS = {
            "左转", "右转", "前进", "后退", "停止", "加速", "减速", "上锁", "解锁"
    };
    private static final String[] UAV_CMDS = {
            "向前", "向後", "無人機向左飛行", "無人機向右飛行", "向上", "向下", "無人機起飞", "無人機降落", "無人機懸停", "向左旋转", "向右旋转"
    };

    private String buildGrammar() {
        String[] arr = (currentMode == VehicleMode.CAR) ? CAR_CMDS : UAV_CMDS;
        return "[\"" + TextUtils.join("\",\"", arr) + "\"]";
    }

    private static String collapseDuplicates(String s) {
        String[] toks = s.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        String prev = null;
        for (String t : toks) {
            if (t.isEmpty()) continue;
            if (!t.equals(prev)) {
                if (out.length() > 0) out.append(' ');
                out.append(t);
                prev = t;
            }
        }
        return out.toString();
    }


    // 啟動語音識別
    private void startSpeechRecognition() {
        stopSpeechRecognition("restart before start");  // 確保先停止並釋放資源
        lastExecutedCommand = "";
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Request permission if not granted
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }
        try {
            rec = new Recognizer(model, 16000.f, buildGrammar());
            rec.setWords(true);
            int minBuf = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minBuf, FRAME_BYTES * 50);
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
            );
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("AudioRecord init failed");
            }

            recorder.startRecording();
            recState = RecState.RUNNING;
            sessionStartTime = SystemClock.elapsedRealtime();
            Log.i(TAG, "錄音開始:"+ sessionStartTime);
            if (saveAudio) {
                try {
                    String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                            java.util.Locale.getDefault()).format(new java.util.Date());
                    File out = new File(outDir, "rec_" + ts + ".wav");
                    wavWriter = new WavWriter(out, SAMPLE_RATE, 1, 16);
                } catch (IOException e) {
                    Log.w(TAG, "開啟 WAV 檔失敗，改為不錄檔", e);
                    wavWriter = null;
                }
            }
            updateSavingBadge();
            if (noiseSuppressionEnabled) {
                applyNoiseSuppressor();
            }
            startAudioWriterThread();
            Log.d("VoskActivity", "Recognizer 重新啟動，模式 = " + currentMode);
        } catch (IOException e) {
            Log.e("VoskActivity", "重啟語音識別失敗", e);
            setErrorState(e.getMessage());
        }
    }

    private void startAudioWriterThread() {
        isRecording = true;
        audioWriterThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            byte[] frameBytes = new byte[FRAME_BYTES];
            short[] audioFrame = new short[FRAME_SAMPLES];
            ByteBuffer bb = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);

            Log.d(TAG, "音訊寫入線程已啟動");

            while (recState != RecState.STOPPED && !Thread.currentThread().isInterrupted()) {
                // 1) 從已存在的 recorder 讀資料
                int nread = recorder.read(frameBytes, 0, FRAME_BYTES, AudioRecord.READ_BLOCKING);
                if (nread <= 0) continue; // 讀失敗或 0 長度就跳過
                if (saveAudio && wavWriter != null && recState != RecState.PAUSED) {
                    try {
                        wavWriter.append(frameBytes, nread);
                    } catch (IOException ignore) {
                    }
                }

                boolean isSpeech = false;
                if (recState == RecState.PAUSED) {
                    Arrays.fill(frameBytes, (byte) 0);
                    wasSpeech = false;
                } else {
                    bb.clear();
                    bb.put(frameBytes);
                    bb.flip();
                    bb.asShortBuffer().get(audioFrame);

                    isSpeech = vad.isSpeech(audioFrame);
                    if (isSpeech && !wasSpeech) {
                        latencyRecordRef.set(new LatencyRecord(System.nanoTime()));
                        Log.d("LATENCY_TRACK", "語音上升沿觸發 (T1)");
                    }
                    LatencyRecord r = latencyRecordRef.get();
                    if (r != null) r.isVoiceFrame = isSpeech;
                    wasSpeech = isSpeech;
                }
                long now = SystemClock.elapsedRealtime();
                if (isSpeech) {
                    if (!inUtterance) {
                        inUtterance = true;
                        utterStartMs = now;
                        tailSilenceMs = 0;
                    } else {
                        tailSilenceMs = 0;
                    }
                    boolean isFinal = rec.acceptWaveForm(frameBytes, nread);
                    if (isFinal) {
                        long finalTimeMs = SystemClock.elapsedRealtime();
                        long latencyMs = finalTimeMs - utterStartMs;
                        double latencySec = latencyMs / 1000.0;

                        Log.i("LATENCY_E2E",
                                String.format(Locale.getDefault(),
                                        "Mic→FinalResult: %d ms (%.3f s)", latencyMs, latencySec));
                        runOnUiThread(() -> successText.setText(
                                String.format(Locale.getDefault(),
                                        "本次指令耗時：%d ms (%.3f s)", latencyMs, latencySec)
                        ));
                        String result = rec.getResult();
                        runOnUiThread(() -> onResult(result));
                        inUtterance = false;
                        tailSilenceMs = 0;
                        try {
                            rec.reset();
                        } catch (Throwable t) {
                            try {
                                rec.close();
                            } catch (Throwable ignore) {
                            }
                            try {
                                rec = new Recognizer(model, SAMPLE_RATE, buildGrammar());
                            } catch (IOException e) {
                                Log.e(TAG, "重建 Recognizer 失敗", e);
                                setErrorState(e.getMessage());
                                break; // 或 return/停止錄音線程，看你的流程
                            }
                        }
                    } else {
                        String partial = rec.getPartialResult();
                        runOnUiThread(() -> onPartialResult(partial));
                    }
                } else {
                    if (inUtterance) {
                        // 尾端靜音累計（你每幀 20ms；若已在 while 外設 frameMs 就用 frameMs）
                        tailSilenceMs += 20;

                        // 收尾條件：靜音超過門檻，或語段太長
                        if (tailSilenceMs >= SILENCE_THRESHOLD || (now - utterStartMs) >= MAX_UTTER_MS) {
                            // 主動要 final
                            long finalTimeMs = now; // 這裡的 now 已經是 SystemClock.elapsedRealtime()
                            long latencyMs = finalTimeMs - utterStartMs;
                            double latencySec = latencyMs / 1000.0;

                            Log.i("LATENCY_E2E",
                                    String.format(Locale.getDefault(),
                                            "[SilenceEnd] Mic→FinalResult: %d ms (%.3f s)",
                                            latencyMs, latencySec));

                            runOnUiThread(() -> successText.setText(
                                    String.format(Locale.getDefault(),
                                            "本次指令耗時：%d ms (%.3f s)", latencyMs, latencySec)
                            ));
                            String finalJson = rec.getFinalResult();
                            runOnUiThread(() -> onResult(finalJson));

                            inUtterance = false;
                            tailSilenceMs = 0;
                            try {
                                rec.reset();
                            } catch (Throwable t) {
                                try {
                                    rec.close();
                                } catch (Throwable ignore) {
                                }
                                try {
                                    rec = new Recognizer(model, SAMPLE_RATE, buildGrammar());
                                } catch (IOException e) {
                                    Log.e(TAG, "重建 Recognizer 失敗", e);
                                    setErrorState(e.getMessage());
                                    break; // 或 return/停止錄音線程，看你的流程
                                }
                            }
                        }
                    }
                }

            }

            // 清理（這裡不釋放 recorder，統一在 stopSpeechRecognition）
            Log.d(TAG, "音訊寫入線程已停止");
        }, "AudioWriter");
        audioWriterThread.start();
    }

    private void stopSpeechRecognition(String reason) {
        Log.i(TAG, "stopSpeechRecognition() reason= " + reason);
        if (recState == RecState.STOPPED) return;
        recState = RecState.STOPPED;
        isRecording = false;
        wasSpeech = false;
        latencyRecordRef.set(null);
        Thread writerThread = audioWriterThread;
        AudioRecord ar = recorder;

        // (1) 先停錄，解除 read() 阻塞，再釋放 NS 和 AudioRecord
        try {
            if (ar != null && ar.getState() == AudioRecord.STATE_INITIALIZED) {
                try {
                    ar.stop();
                } catch (IllegalStateException ignore) {
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "AudioRecord.stop() error", t);
        } finally {
            try {
                releaseNoiseSuppressor();
            } catch (Throwable ignore) {
            }
            if (ar != null) {
                try {
                    ar.release();
                } catch (Throwable ignore) {
                }
            }
            recorder = null;
        }

        // (2) 再等錄音線程結束（加 timeout；若還活著就 interrupt 一次）
        if (writerThread != null) {
            try {
                writerThread.join(600);
                if (writerThread.isAlive()) {
                    writerThread.interrupt();       // 防止非預期阻塞
                    writerThread.join(600);
                }
            } catch (InterruptedException ignore) {
            }
            audioWriterThread = null;
        }

        // (3) 關閉 Vosk recognizer
        if (rec != null) {
            try {
                rec.close();
            } catch (Exception ignore) {
            }
            rec = null;
        }

        // (4) 停掉排程
        if (repeatTask != null) {
            repeatTask.cancel(false);
            repeatTask = null;
        }
        if (stopTask != null) {
            stopTask.cancel(false);
            stopTask = null;
        }

        // (5) 收尾：關 WAV 寫檔（回填 header）
        if (wavWriter != null) {
            try {
                wavWriter.close();
            } catch (IOException e) {
                Log.w(TAG, "wavWriter.close()", e);
            }
            wavWriter = null;
        }

        frameCnt = 0;

        // (6) 更新徽章（若這裡可能從背景執行緒呼叫，保險用 UI 執行）
        try {
            runOnUiThread(this::updateSavingBadge);
        } catch (Exception e) {
            // 若已在 UI 執行緒，直接呼叫
            updateSavingBadge();
        }
        if(sessionStartTime >0L){
            sessionEndTime = SystemClock.elapsedRealtime();
            long usedMs = sessionEndTime - sessionStartTime;
            double usedSec = usedMs/1000.0;
            double usedMin = usedSec/60.0;
            Log.i("USETIME",String.format(Locale.getDefault(),"使用時間：%.2f 秒",usedSec,usedMin));
            runOnUiThread(() -> successText.setText(
                    String.format(Locale.getDefault(),
                            "本次錄音使用時間：%.1f 秒 (約 %.2f 分鐘)", usedSec, usedMin)
            ));

            // 重置，避免下次繼續累計
            sessionStartTime = 0L;
        }

    }
}