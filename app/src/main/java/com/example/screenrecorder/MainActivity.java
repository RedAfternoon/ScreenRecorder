package com.example.screenrecorder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_CAPTURE = 1001;
    private static final int REQ_PERMISSIONS = 1002;

    private Button toggleButton;
    private CheckBox micCheck;
    private TextView statusText;
    private boolean waitingToStart = false;

    private final Runnable stateListener = () -> {
        updateUi();
        // Once recording has really started, get out of the way so the user can record other apps.
        if (waitingToStart && ScreenRecordService.isRecording) {
            waitingToStart = false;
            moveTaskToBack(true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toggleButton = findViewById(R.id.toggle_button);
        micCheck = findViewById(R.id.mic_check);
        statusText = findViewById(R.id.status_text);

        toggleButton.setOnClickListener(v -> {
            if (ScreenRecordService.isRecording) {
                startService(new Intent(this, ScreenRecordService.class)
                        .setAction(ScreenRecordService.ACTION_STOP));
            } else {
                requestPermissionsThenCapture();
            }
        });
        findViewById(R.id.recordings_button).setOnClickListener(v ->
                startActivity(new Intent(this, RecordingsActivity.class)));
    }

    @Override
    protected void onStart() {
        super.onStart();
        ScreenRecordService.stateListener = stateListener;
        updateUi();
    }

    @Override
    protected void onStop() {
        // Keep listening while we wait for the service, so we can move to the background.
        if (!waitingToStart) ScreenRecordService.stateListener = null;
        super.onStop();
    }

    private void updateUi() {
        boolean recording = ScreenRecordService.isRecording;
        toggleButton.setText(recording ? "Stop recording" : "Start recording");
        statusText.setText(recording ? "● Recording…" : "Ready");
        micCheck.setEnabled(!recording);
    }

    private void requestPermissionsThenCapture() {
        List<String> needed = new ArrayList<>();
        if (micCheck.isChecked()) needed.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);

        List<String> missing = new ArrayList<>();
        for (String p : needed) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        if (missing.isEmpty()) {
            launchCapture();
        } else {
            requestPermissions(missing.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSIONS) return;
        for (int i = 0; i < permissions.length; i++) {
            if (results[i] == PackageManager.PERMISSION_GRANTED) continue;
            if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])) {
                micCheck.setChecked(false);
                Toast.makeText(this, "Microphone denied – recording without sound", Toast.LENGTH_SHORT).show();
            } else if (Manifest.permission.WRITE_EXTERNAL_STORAGE.equals(permissions[i])) {
                Toast.makeText(this, "Storage permission is needed to save videos", Toast.LENGTH_LONG).show();
                return;
            }
        }
        launchCapture();
    }

    private void launchCapture() {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen recording was not allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent service = new Intent(this, ScreenRecordService.class)
                .setAction(ScreenRecordService.ACTION_START)
                .putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(ScreenRecordService.EXTRA_DATA, data)
                .putExtra(ScreenRecordService.EXTRA_MIC, micCheck.isChecked());
        waitingToStart = true;
        ScreenRecordService.stateListener = stateListener;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
    }
}
