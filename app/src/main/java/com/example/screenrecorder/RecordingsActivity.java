package com.example.screenrecorder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.util.LruCache;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists the videos in Movies/ScreenRecorder and lets you play, share or delete them. */
public class RecordingsActivity extends Activity {

    private static final int REQ_READ = 1;
    private static final int REQ_DELETE = 2;

    static class Item {
        long id;
        Uri uri;
        String name;
        long durationMs;
        long size;
        long dateAddedSec;
    }

    private final List<Item> items = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<Long, Bitmap> thumbs = new LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        @Override
        protected int sizeOf(Long key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private final Set<Long> loadingThumbs = new HashSet<>();
    private final Set<Long> failedThumbs = new HashSet<>();

    private RecordingAdapter adapter;
    private boolean askedPermission = false;
    private Item pendingDelete;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recordings);
        setTitle("My recordings");

        ListView list = findViewById(R.id.list);
        list.setEmptyView(findViewById(R.id.empty_text));
        adapter = new RecordingAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> play(items.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            showOptions(items.get(position));
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        String perm = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED && !askedPermission) {
            askedPermission = true;
            requestPermissions(new String[]{perm}, REQ_READ);
        }
        // On Android 10+ we can always see the videos this app made, even without the permission.
        load();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_READ) load();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    // ---------- Loading ----------

    @SuppressWarnings("deprecation")
    private void load() {
        executor.execute(() -> {
            List<Item> found = new ArrayList<>();
            Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_ADDED
            };
            String selection;
            String[] args;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?";
                args = new String[]{Environment.DIRECTORY_MOVIES + "/ScreenRecorder%"};
            } else {
                selection = MediaStore.Video.Media.DATA + " LIKE ?";
                args = new String[]{"%/" + Environment.DIRECTORY_MOVIES + "/ScreenRecorder/%"};
            }
            try (Cursor c = getContentResolver().query(collection, projection, selection, args,
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (c != null) {
                    while (c.moveToNext()) {
                        Item it = new Item();
                        it.id = c.getLong(0);
                        it.uri = ContentUris.withAppendedId(collection, it.id);
                        it.name = c.getString(1);
                        it.durationMs = c.getLong(2);
                        it.size = c.getLong(3);
                        it.dateAddedSec = c.getLong(4);
                        found.add(it);
                    }
                }
            } catch (Exception e) {
                main.post(() -> Toast.makeText(this, "Couldn't read recordings: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
            main.post(() -> {
                items.clear();
                items.addAll(found);
                adapter.notifyDataSetChanged();
            });
        });
    }

    @SuppressWarnings("deprecation")
    private Bitmap loadThumbnail(Item it) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return getContentResolver().loadThumbnail(it.uri, new Size(256, 256), null);
            } else {
                return MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), it.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null);
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Actions ----------

    private void play(Item it) {
        startActivity(new Intent(this, PlayerActivity.class).setData(it.uri));
    }

    private void share(Item it) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, it.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(send, "Share recording"));
    }

    private void showOptions(Item it) {
        new AlertDialog.Builder(this)
                .setTitle(it.name)
                .setItems(new String[]{"Play", "Share", "Delete"}, (d, which) -> {
                    if (which == 0) play(it);
                    else if (which == 1) share(it);
                    else confirmDelete(it);
                })
                .show();
    }

    private void confirmDelete(Item it) {
        new AlertDialog.Builder(this)
                .setTitle("Delete recording?")
                .setMessage(it.name)
                .setPositiveButton("Delete", (d, w) -> delete(it))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void delete(Item it) {
        try {
            int rows = getContentResolver().delete(it.uri, null, null);
            Toast.makeText(this, rows > 0 ? "Deleted" : "Couldn't delete", Toast.LENGTH_SHORT).show();
            load();
        } catch (SecurityException e) {
            // Happens for videos made before the app was reinstalled: Android asks the user to confirm.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(),
                            Collections.singletonList(it.uri));
                    startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
                } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
                        && e instanceof RecoverableSecurityException) {
                    pendingDelete = it;
                    IntentSender sender = ((RecoverableSecurityException) e)
                            .getUserAction().getActionIntent().getIntentSender();
                    startIntentSenderForResult(sender, REQ_DELETE, null, 0, 0, 0);
                } else {
                    Toast.makeText(this, "No permission to delete this video", Toast.LENGTH_LONG).show();
                }
            } catch (IntentSender.SendIntentException ex) {
                Toast.makeText(this, "Couldn't delete", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_DELETE || resultCode != RESULT_OK) return;
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && pendingDelete != null) {
            Item it = pendingDelete;
            pendingDelete = null;
            delete(it); // permission granted, try again
        } else {
            Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show();
            load();
        }
    }

    // ---------- List ----------

    private static String formatDuration(long ms) {
        if (ms <= 0) return "–:––";
        long s = ms / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private class RecordingAdapter extends BaseAdapter {
        private final DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return items.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(RecordingsActivity.this).inflate(R.layout.item_recording, parent, false);
            Item it = items.get(position);
            ((TextView) v.findViewById(R.id.name)).setText(it.name);
            ((TextView) v.findViewById(R.id.details)).setText(
                    formatDuration(it.durationMs) + "  ·  "
                            + Formatter.formatShortFileSize(RecordingsActivity.this, it.size) + "  ·  "
                            + dateFormat.format(new Date(it.dateAddedSec * 1000)));

            ImageView thumb = v.findViewById(R.id.thumb);
            Bitmap bmp = thumbs.get(it.id);
            if (bmp != null) {
                thumb.setImageBitmap(bmp);
            } else {
                thumb.setImageResource(R.drawable.ic_video);
                if (!loadingThumbs.contains(it.id) && !failedThumbs.contains(it.id)) {
                    loadingThumbs.add(it.id);
                    executor.execute(() -> {
                        Bitmap loaded = loadThumbnail(it);
                        main.post(() -> {
                            loadingThumbs.remove(it.id);
                            if (loaded != null) thumbs.put(it.id, loaded);
                            else failedThumbs.add(it.id);
                            notifyDataSetChanged();
                        });
                    });
                }
            }
            return v;
        }
    }
}
