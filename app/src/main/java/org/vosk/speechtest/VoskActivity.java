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

import java.util.Arrays;

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
    private int     tailSilenceMs = 0;               // 語段尾巴的靜音累計
    private long    utterStartMs  = 0;               // 語段起點時間

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
    private ScheduledFuture<?> repeatTask;
    private ScheduledFuture<?> stopTask;
    private byte[] lastPacket = null;
    private static final long REPEAT_INTERVAL = 3000;
    private boolean isModelLoaded = false;

    private TcpClient tcpClient;
    private AppDatabase db;
    private VehicleMode currentMode = VehicleMode.CAR;
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
    private enum RecState { STOPPED, RUNNING, PAUSED }
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
        nsSwitch.setChecked(noiseSuppressionEnabled);
        setUiState(STATE_START);
        ToggleButton pauseButton = findViewById(R.id.pause);
        pauseButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if(!buttonView.isPressed())return;
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
        tcpClient = new TcpClient("192.168.0.148", 8080, Executors.newSingleThreadExecutor());
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
                    String packet = instructionDao.findPacketByKeyword(keyword,currentMode.name());
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
            String finalText = json.optString("text", "");

            if (TextUtils.isEmpty(finalText)) return;
            String collapsed = collapseDuplicates(finalText);
            String traditionalPartial = SIMPLIFIED_TO_TRADITIONAL.transliterate(collapsed)
                    .replaceAll("\\s+", ""); // 移除所有空格
            if (traditionalPartial.isEmpty()) return;
            String hit = extractHit(traditionalPartial);
            if (hit ==null) return;
            if (record != null) {
                if (!hit.equals(record.partialCommand)) {
                    record.partialCommand = hit; //
                    record.T2_partialOkNano = System.nanoTime();
                }
            }
            if (record != null) {
                switch (hit) {
                    case "加速": voiceSpeedUp (record); break;
                    case "減速": voiceSpeedDown(record); break;
                    default    : generatePacket(Collections.singletonList(hit), record);
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


            if (!firstCommandSent) {
                firstCommandSent = true;
                sendRawPacket(new byte[]{(byte)0xA5, (byte)0x81, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFA});
                sendRawPacket(new byte[]{(byte)0xA5, (byte)0x80, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFA});
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
            lastPacket   = packet;


            sendRawPacket(packet, record);


            boolean isTurn = keywords.contains("左轉") || keywords.contains("右轉");
            if (isTurn) {
                scheduler.schedule(() -> sendRawPacket(packet), 1000, TimeUnit.MILLISECONDS);
            }


            if (repeatTask != null) repeatTask.cancel(false);
            repeatTask = scheduler.scheduleWithFixedDelay(() -> sendRawPacket(packet),
                    REPEAT_INTERVAL, REPEAT_INTERVAL, TimeUnit.MILLISECONDS);
        });
    }


    private void sendRawPacket(byte[] packet, LatencyRecord record) {
        long T3_packetSendNano = System.nanoTime();


        if (record.T1_speechStartNano > 0 && record.T2_partialOkNano > 0) {
            double tMicToOk   = (record.T2_partialOkNano - record.T1_speechStartNano) / 1_000_000.0;
            double tOkToSend  = (T3_packetSendNano - record.T2_partialOkNano) / 1_000_000.0;
            double tEndToEnd  = (T3_packetSendNano - record.T1_speechStartNano) / 1_000_000.0;

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
            sendRawPacket(packet,record);
            runOnUiThread(() -> {
                tvpacket.setText(HexUtils.byteToHexString(packet));
                tvSpeed.setText("速度 : " + speed);
            });
        });
    }
    private void applyNoiseSuppressor() {
        if(!noiseSuppressionEnabled) return;
        if(!NoiseSuppressor.isAvailable()|| recorder == null) return;
        try{
            if(ns !=null){
                try {ns.release();}catch(Throwable e){}
                ns = null;
            }
            ns =NoiseSuppressor.create(recorder.getAudioSessionId());
            if (ns !=null) ns.setEnabled(true);
            Log.d(TAG, "降躁已啟用");
        }catch(Throwable e){
            Log.w(TAG,"啟用降躁失敗",e);
        }
    }
    private void releaseNoiseSuppressor(){
        if(ns !=null){
            try {ns.setEnabled(false);}catch(Throwable ignore){}
            try {ns.release();}catch(Throwable ignore){}
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


    private static final String[] CAR_CMDS  = {
            "左转","右转","前进","后退","停止","加速","减速","上锁","解锁"
    };
    private static final String[] UAV_CMDS = {
            "向前","向后","向左邊","向右邊","向上","向下","起飞","降落","懸停","向左旋转","向右旋转"
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
            rec = new Recognizer(model, 16000.f,buildGrammar());
            int minBuf = AudioRecord.getMinBufferSize(16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufferSize = Math.max(minBuf,FRAME_BYTES * 50);
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
                long now =SystemClock.elapsedRealtime();
                if (isSpeech) {
                    if (!inUtterance){
                        inUtterance = true;
                        utterStartMs = now;
                        tailSilenceMs = 0;
                    }else{
                        tailSilenceMs =0;
                    }
                    boolean isFinal = rec.acceptWaveForm(frameBytes, nread);
                    if (isFinal) {
                        String result = rec.getResult();
                        runOnUiThread(() -> onResult(result));
                        inUtterance = false;
                        tailSilenceMs = 0;
                        try{
                            rec.reset();
                        }catch (Throwable t){
                            try {
                                rec.close();
                            }catch (Throwable ignore){}
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
                }else {
                    if (inUtterance) {
                        // 尾端靜音累計（你每幀 20ms；若已在 while 外設 frameMs 就用 frameMs）
                        tailSilenceMs += 20;

                        // 收尾條件：靜音超過門檻，或語段太長
                        if (tailSilenceMs >= SILENCE_THRESHOLD || (now - utterStartMs) >= MAX_UTTER_MS) {
                            // 主動要 final
                            String finalJson = rec.getFinalResult();
                            runOnUiThread(() -> onResult(finalJson));

                            inUtterance = false;
                            tailSilenceMs = 0;
                            try {
                                rec.reset();
                            } catch (Throwable t) {
                                try { rec.close(); } catch (Throwable ignore) {}
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
        wasSpeech   = false;
        latencyRecordRef.set(null);



        if (audioWriterThread != null) {
            try { audioWriterThread.join(); } catch (InterruptedException ignored) {}
            audioWriterThread = null;
        }
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Exception ignore) {}
        recorder = null;
        releaseNoiseSuppressor();
        if (rec != null) {
            try { rec.close(); } catch (Exception ignore) {}
            rec = null;
        }


        if (repeatTask != null) { repeatTask.cancel(false); repeatTask = null; }
        if (stopTask   != null) { stopTask.cancel(false);   stopTask   = null; }
        frameCnt = 0;
    }

}