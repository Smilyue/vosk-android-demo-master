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

package org.vosk.demo;

import static android.service.controls.ControlsProviderService.TAG;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.icu.text.SimpleDateFormat;
import android.icu.text.Transliterator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

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

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
    private int retryCount = 0;

    private ExecutorService executorService = Executors.newFixedThreadPool(6);
    private boolean isModelLoaded = false;
    private TcpClient tcpClient;
    private AppDatabase db;
    /* Used to handle permission request */
    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;

    private static final Transliterator SIMPLIFIED_TO_TRADITIONAL =
            Transliterator.getInstance("Simplified-Traditional");
    private Model model;
    private List<String> keywordCache = new ArrayList<>();
    private SpeechService speechService;
    private SpeechStreamService speechStreamService;
    private TextView resultView;
    private TextView tvpacket;
    private TextView successText;
    private TextView tvKeywords;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        // Setup layout
        resultView = findViewById(R.id.result_text);
        tvpacket = findViewById(R.id.tv_packet);
        successText = findViewById(R.id.successText);
        tvKeywords = findViewById(R.id.tvKeywords);
        db = AppDatabase.getDatabase(this);
        if (db != null) {
            Log.d("VoskActivity", "数据库已成功初始化");
        } else {
            Log.e("VoskActivity", "数据库初始化失败");
        }
        Log.d("VoskActivity", "数据库初始化完成");
        setUiState(STATE_START);
        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));
        tcpClient = new TcpClient("192.168.0.79",8080,Executors.newSingleThreadExecutor());
        tcpClient.initializeTcpConnection();
        LibVosk.setLogLevel(LogLevel.INFO);
        // Check if user has given permission to record audio, init the model after permission is granted
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }
    }

    private void initModel() {
        StorageService.unpack(this, "vosk-model-cn-kaldi-multicn-0.15", "model",
                (model) -> {
                    this.model = model;
                    isModelLoaded = true;
                    setUiState(STATE_READY);
                },
                (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }
    private void loadkeyword(){
        executorService.execute(()->{
            if (db == null) {
                Log.e("VoskActivity", "Database is not initialized");
                return;
            }
            InstructionDao instructionDao = db.instructionDao();
            keywordCache = instructionDao.getAllKeywords(); // 从数据库加载关键字到缓存
            Log.d("Cache", "關鍵字已儲存：" + keywordCache.toString());
        });
    }
    private List<String> getKeywordsFromCache() {
        if (keywordCache == null || keywordCache.isEmpty()) {
            loadkeyword();
        }
        return keywordCache;
    }




    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Recognizer initialization is a time-consuming and it involves IO,
                // so we execute it in async task
                initModel();
            } else {
                finish();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }
        if (tcpClient != null) {
            tcpClient.closeTcpConnection(); // 關閉 TCP 連接
        }

        if (speechStreamService != null) {
            speechStreamService.stop();
        }
    }

    @Override
    public void onResult(String hypothesis) {
        String traditionalResult = SIMPLIFIED_TO_TRADITIONAL.transliterate(hypothesis);
        resultView.append(traditionalResult + "\n");
    }

    @Override
    public void onFinalResult(String hypothesis) {
        try {
            JSONObject jsonObject = new JSONObject(hypothesis);
            String extractedText = jsonObject.optString("text", "");
            Log.d("VoskActivity", "Extracted Text: " + extractedText);
            String traditionalResult = SIMPLIFIED_TO_TRADITIONAL.transliterate(extractedText);
            Log.d("VoskActivity", "Traditional Result: " + traditionalResult);
            executorService.execute(() -> {
                if (!tcpClient.isTcpConnected()){
                     Log.w("VoskActivity","TCP未連接");
                     return;
                }
                if (!isModelLoaded){
                    Log.w("VoskActivity","模型未加載");
                    return;
                }
                List<String> keywords = getKeywordsFromCache();
                List<String> matchedKeywords = new ArrayList<>();
                for (String keyword : keywords) {
                    if (traditionalResult.contains(keyword.trim())) {
                        matchedKeywords.add(keyword);
                    }
                }

                runOnUiThread(() -> {
                    if (!matchedKeywords.isEmpty()) {
                        generatePacket(matchedKeywords);
                        updateUI(matchedKeywords, traditionalResult);
                    }
                });
            });
            // 設置狀態為完成
            setUiState(STATE_DONE);

            // 釋放資源
            if (speechStreamService != null) {
                speechStreamService = null;
            }
        }catch (JSONException e) {
            Log.e("VoskActivity", "Error parsing hypothesis JSON", e);
        }

    }
    private void generatePacket(List<String> keywords) {
        executorService.execute(() -> {
            if (!tcpClient.isTcpConnected() || !isModelLoaded) {
                Log.e(TAG, "TCP 連接未建立或模型未載入，無法生成封包");
                return;
            }
            InstructionDao instructionDao = db.instructionDao();
            StringBuilder packet = new StringBuilder();
            packet.append("A5 ");
            Log.d("資料庫連接", "已連接");
            for (String keyword : keywords) {
                Integer intValue = instructionDao.findIntValueByKeyword(keyword);
                if (intValue != null) {
                    packet.append(intValue).append(" ");
                }
            }

            packet.append("FA");
            runOnUiThread(() -> tvpacket.setText(packet.toString()));
            tcpClient.sendPacket(packet.toString());

        });
    }
    private void updateUI(List<String> matchedKeywords, String traditionalResult) {
        if (!matchedKeywords.isEmpty()) {
            StringBuilder keywordText = new StringBuilder("匹配的关键字：\n");
            for (String keyword : matchedKeywords) {
                keywordText.append(keyword).append("\n");
            }
            keywordText.append("時間：")
                    .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            tvKeywords.setText(keywordText.toString());
        } else {
            tvKeywords.setText("未匹配到任何关键字。");
        }
        resultView.setText("講述的内容：\n" + traditionalResult);
    }



    @Override
    public void onPartialResult(String hypothesis) {
        Log.d("VoskActivity", "Intermediate Result: " + SIMPLIFIED_TO_TRADITIONAL.transliterate(hypothesis));
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.recognize_file);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                ((ToggleButton) findViewById(R.id.pause)).setChecked(false);
                break;
            case STATE_FILE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.stop_file);
                resultView.setText(getString(R.string.starting));
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((true));
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
                Recognizer rec = new Recognizer(model, 16000.f, "[\"右轉\", " +
                        "\"左轉\","+" \"前進\",\"後退\",\"降落\"]");

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

    private void recognizeMicrophone() {
        if (speechService != null) {
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null;
        } else {
            setUiState(STATE_MIC);
            try {
                Recognizer rec = new Recognizer(model, 16000.f, "[\"右轉\", " +
                        "\"左轉\"," + "\"前進\"," + "\"後退\"," + "\"降落\"]");
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }


    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }

}
