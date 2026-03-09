package org.vosk.speechtest;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MVP 三場景版 CSV Logger
 * 一行 = 一次 final result（utterance）
 *
 * CSV 欄位：
 * timestamp,scene,env_db,expected_cmd,recognized_text,hit,avg_conf,success,asr_latency_ms
 */
public class MvpCsvLogger {

    private static final String TAG = "MVP_CSV";
    private final File csvFile;
    private final Object lock = new Object();

    public MvpCsvLogger(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "experiments");
        if (!dir.exists()) dir.mkdirs();
        csvFile = new File(dir, "mvp_scene_log.csv");
        ensureHeader();
    }

    public File getCsvFile() {
        return csvFile;
    }

    private void ensureHeader() {
        if (csvFile.exists() && csvFile.length() > 0) return;
        appendRaw("timestamp,scene,env_db,expected_cmd,recognized_text,hit,avg_conf,success,asr_latency_ms\n");
    }

    public void logRow(
            String scene,
            int envDb,
            String expectedCmd,
            String recognizedText,
            String hit,
            double avgConf,
            boolean success,
            long latencyMs
    ) {
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());

        String line = csv(ts) + "," +
                csv(scene) + "," +
                envDb + "," +
                csv(expectedCmd) + "," +
                csv(recognizedText) + "," +
                csv(hit) + "," +
                fmt(avgConf) + "," +
                (success ? "true" : "false") + "," +
                latencyMs + "\n";

        appendRaw(line);
    }

    private String fmt(double v) {
        if (v < 0) return "-1";
        return String.format(Locale.US, "%.6f", v);
    }

    private String csv(String s) {
        if (s == null) s = "";
        s = s.replace("\r", " ").replace("\n", " ");
        boolean needQuote = s.contains(",") || s.contains("\"");
        if (s.contains("\"")) s = s.replace("\"", "\"\"");
        return needQuote ? ("\"" + s + "\"") : s;
    }

    private void appendRaw(String text) {
        synchronized (lock) {
            try (FileWriter fw = new FileWriter(csvFile, true)) {
                fw.append(text);
            } catch (Exception e) {
                Log.w(TAG, "append failed: " + e.getMessage());
            }
        }
    }
}

