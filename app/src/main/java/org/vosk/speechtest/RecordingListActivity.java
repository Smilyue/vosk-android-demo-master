package org.vosk.speechtest;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.*;
import androidx.core.content.FileProvider;


public class RecordingListActivity extends AppCompatActivity  {

    private RecyclerView rv;
    private TextView empty;
    private RecordingAdapter adapter;
    private File outDir;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("錄音檔");
        setContentView(R.layout.activity_recording_list);

        rv = findViewById(R.id.rvRecordings);
        empty = findViewById(R.id.emptyView);

        adapter = new RecordingAdapter(this, new RecordingAdapter.Callbacks() {
            @Override public void onOpen(File f) { openWithExternalApp(f); }
            @Override public void onSelectionChanged(int count) { updateActionBar(count); }
        });

        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rv.setAdapter(adapter);

        // 你的主專案用的是 getExternalFilesDir("VoskTest")
        outDir = new File(getExternalFilesDir(null), "VoskTest");
        if (!outDir.exists()) outDir.mkdirs();

        loadFiles();
    }

    private void loadFiles() {
        List<File> files = listWavFiles(outDir);
        adapter.submit(files);
        empty.setVisibility(files.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        // 進入頁面先清空任何殘留選取
        adapter.clearSelection();
    }

    private static List<File> listWavFiles(File dir) {
        if (dir == null) return Collections.emptyList();
        File[] arr = dir.listFiles((d, name) -> name.toLowerCase(Locale.ROOT).endsWith(".wav"));
        if (arr == null) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(arr));
        list.sort((a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    // ====== 分享/開啟/刪除 ======

    private void openWithExternalApp(File f) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent it = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "audio/wav")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(it); }
        catch (Exception e) { Toast.makeText(this, "沒有可開啟的 App", Toast.LENGTH_SHORT).show(); }
    }

    private void shareSelected() {
        List<File> sel = adapter.getSelected();
        if (sel.isEmpty()) { Toast.makeText(this, "請先選取檔案", Toast.LENGTH_SHORT).show(); return; }

        if (sel.size() == 1) {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", sel.get(0));
            Intent it = new Intent(Intent.ACTION_SEND)
                    .setType("audio/wav")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享音檔"));
        } else {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : sel) {
                uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f));
            }
            Intent it = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType("audio/*")
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(it, "分享音檔（多個）"));
        }
    }

    private void deleteSelected() {
        List<File> sel = adapter.getSelected();
        if (sel.isEmpty()) { Toast.makeText(this, "請先選取檔案", Toast.LENGTH_SHORT).show(); return; }

        new android.app.AlertDialog.Builder(this)
                .setTitle("刪除確認")
                .setMessage("確定刪除選取的 " + sel.size() + " 個檔案？此動作無法復原。")
                .setPositiveButton("刪除", (d, w) -> {
                    int ok = 0;
                    for (File f : sel) {
                        try { if (f.delete()) ok++; } catch (Exception ignore) {}
                    }
                    Toast.makeText(this, "已刪除 " + ok + " 個檔案", Toast.LENGTH_SHORT).show();
                    adapter.removeFiles(sel);
                    empty.setVisibility(adapter.getItemCount()==0 ? android.view.View.VISIBLE : android.view.View.GONE);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ====== ActionBar 上顯示選取數 + 動作 ======

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_recording_list, menu);
        return true;
    }

    @Override public boolean onPrepareOptionsMenu(android.view.Menu menu) {
        int count = adapter.getSelected().size();
        menu.findItem(R.id.action_share).setEnabled(count > 0);
        menu.findItem(R.id.action_delete).setEnabled(count > 0);
        menu.findItem(R.id.action_select_all).setEnabled(adapter.getItemCount() > 0);
        menu.findItem(R.id.action_clear).setEnabled(count > 0);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_share) { shareSelected(); return true; }
        if (id == R.id.action_delete) { deleteSelected(); return true; }
        if (id == R.id.action_select_all) { adapter.toggleSelectAll(true); invalidateOptionsMenu(); return true; }
        if (id == R.id.action_clear) { adapter.clearSelection(); invalidateOptionsMenu(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void updateActionBar(int selectedCount) {
        if (selectedCount > 0) setTitle("已選取 " + selectedCount + " 個");
        else setTitle("錄音檔");
        invalidateOptionsMenu();
    }
}

