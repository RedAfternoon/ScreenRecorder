package com.example.screenrecorder;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.MediaController;
import android.widget.Toast;
import android.widget.VideoView;

/** Simple full-screen video player with play/pause and a seek bar. */
public class PlayerActivity extends Activity {

    private static final String KEY_POSITION = "position";

    private VideoView video;
    private MediaController controller;
    private int position = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        Uri uri = getIntent().getData();
        if (uri == null) {
            finish();
            return;
        }
        if (savedInstanceState != null) position = savedInstanceState.getInt(KEY_POSITION, 0);

        video = findViewById(R.id.video);
        controller = new MediaController(this);
        controller.setAnchorView(video);
        video.setMediaController(controller);
        video.setOnPreparedListener(mp -> {
            video.seekTo(position);
            video.start();
            controller.show(3000);
        });
        video.setOnCompletionListener(mp -> controller.show(0));
        video.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Can't play this video", Toast.LENGTH_LONG).show();
            finish();
            return true;
        });
        video.setVideoURI(uri);
    }

    @Override
    protected void onPause() {
        if (video != null) {
            position = video.getCurrentPosition();
            video.pause();
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_POSITION, position);
    }
}
