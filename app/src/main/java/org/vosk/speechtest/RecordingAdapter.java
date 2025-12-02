package org.vosk.speechtest;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

class RecordingAdapter extends RecyclerView.Adapter<RecordingAdapter.VH> {

    interface Callbacks {
        void onOpen(File f);
        void onSelectionChanged(int selectedCount);
    }

    private final LayoutInflater inflater;
    private final List<File> files = new ArrayList<>();
    private final Set<File> selected = new HashSet<>();
    private final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private final Callbacks cb;

    RecordingAdapter(Context ctx, Callbacks cb) {
        this.inflater = LayoutInflater.from(ctx);
        this.cb = cb;
    }

    void submit(List<File> list) {
        files.clear();
        if (list != null) files.addAll(list);
        notifyDataSetChanged();
        notifySelection();
    }

    List<File> getSelected() { return new ArrayList<>(selected); }

    void clearSelection() {
        selected.clear();
        notifyDataSetChanged();
        notifySelection();
    }

    void toggleSelectAll(boolean check) {
        selected.clear();
        if (check) selected.addAll(files);
        notifyDataSetChanged();
        notifySelection();
    }

    void removeFiles(Collection<File> removed) {
        files.removeAll(removed);
        selected.removeAll(removed);
        notifyDataSetChanged();
        notifySelection();
    }

    private void notifySelection() {
        if (cb != null) cb.onSelectionChanged(selected.size());
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(inflater.inflate(R.layout.item_recording, parent, false));
    }

    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        File f = files.get(pos);
        h.tvName.setText(f.getName());
        String meta = android.text.format.Formatter.formatShortFileSize(h.itemView.getContext(), f.length())
                + " • " + sdf.format(new Date(f.lastModified()));
        h.tvMeta.setText(meta);
        h.cb.setOnCheckedChangeListener(null);
        h.cb.setChecked(selected.contains(f));

        h.cb.setOnCheckedChangeListener((b, isChecked) -> {
            if (isChecked) selected.add(f); else selected.remove(f);
            notifySelection();
        });
        h.itemView.setOnClickListener(v -> {
            // 點整列也切換選取
            boolean now = !h.cb.isChecked();
            h.cb.setChecked(now);
        });
        h.btnPlay.setOnClickListener(v -> { if (cb != null) cb.onOpen(f); });
    }

    @Override public int getItemCount() { return files.size(); }

    static class VH extends RecyclerView.ViewHolder {
        CheckBox cb; TextView tvName; TextView tvMeta; ImageView btnPlay;
        VH(@NonNull View v) {
            super(v);
            cb = v.findViewById(R.id.cbSelect);
            tvName = v.findViewById(R.id.tvName);
            tvMeta = v.findViewById(R.id.tvMeta);
            btnPlay = v.findViewById(R.id.btnPlay);
        }
    }
}

