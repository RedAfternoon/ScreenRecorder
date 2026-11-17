package com.example.screenrecorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.Display;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that records the screen (and optionally the microphone)
 * to an MP4 file in Movies/ScreenRecorder.
 */
public class ScreenRecordService extends Service {

    public static final String ACTION_START = "com.example.screenrecorder.START";
    public static final String ACTION_STOP = "com.example.screenrecorder.STOP";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_MIC = "mic";

    private static final String CHANNEL_ID = "screen_recording";
    private static final int NOTIFICATION_ID = 42;
    private static final int MAX_LONG_SIDE = 1920; // keeps MediaTek/Unisoc encoders happy

    /** Read by MainActivity to show the current state. */
    public static volatile boolean isRecording = false;
    /** MainActivity sets this while visible; called on the main thread when state changes. */
    public static Runnable stateListener;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private MediaRecorder recorder;
    private ParcelFileDescriptor outputPfd;
    private Uri outputUri;      // Android 10+
    private File outputFile;    // Android 7-9
    private boolean stopping = false;

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            // User revoked capture (e.g. from the status bar) - finish the file cleanly.
            stopRecording();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
        } else if (ACTION_START.equals(action) && !isRecording) {
            startRecording(intent);
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startRecording(Intent intent) {
        stopping = false;
        boolean mic = intent.getBooleanExtra(EXTRA_MIC, false)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

        // Must be in the foreground BEFORE asking for the MediaProjection (required on Android 10+/14).
        createChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (mic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_DATA);

        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (data == null || mpm == null) {
            fail("Screen capture permission missing");
            return;
        }
        projection = mpm.getMediaProjection(resultCode, data);
        if (projection == null) {
            fail("Could not start screen capture");
            return;
        }
        projection.registerCallback(projectionCallback, handler);

        try {
            // Work out the capture size from the real screen size.
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            int longSide = Math.max(width, height);
            if (longSide > MAX_LONG_SIDE) {
                float scale = (float) MAX_LONG_SIDE / longSide;
                width = Math.round(width * scale);
                height = Math.round(height * scale);
            }
            // Encoders on budget chips prefer sizes divisible by 16.
            width = Math.max(16, (width / 16) * 16);
            height = Math.max(16, (height / 16) * 16);
            int bitrate = (int) Math.min(12_000_000, Math.max(4_000_000, width * height * 30L / 6));

            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(this) : new MediaRecorder();
            if (mic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.setVideoSize(width, height);
            recorder.setVideoFrameRate(30);
            recorder.setVideoEncodingBitRate(bitrate);
            if (mic) {
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioEncodingBitRate(128_000);
                recorder.setAudioSamplingRate(44_100);
            }
            openOutput();
            recorder.prepare();

            virtualDisplay = projection.createVirtualDisplay(
                    "ScreenRecorder", width, height, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    recorder.getSurface(), null, null);

            recorder.start();
            isRecording = true;
            notifyState();
        } catch (Exception e) {
            fail("Recording failed: " + e.getMessage());
        }
    }

    private void openOutput() throws Exception {
        String name = "Screen_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ScreenRecorder");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            ContentResolver resolver = getContentResolver();
            outputUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (outputUri == null) throw new IllegalStateException("Cannot create video file");
            outputPfd = resolver.openFileDescriptor(outputUri, "w");
            recorder.setOutputFile(outputPfd.getFileDescriptor());
        } else {
            @SuppressWarnings("deprecation")
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ScreenRecorder");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            outputFile = new File(dir, name);
            recorder.setOutputFile(outputFile.getAbsolutePath());
        }
    }

    private void stopRecording() {
        if (stopping) return;
        stopping = true;

        boolean saved = false;
        if (recorder != null) {
            try {
                if (isRecording) {
                    recorder.stop();
                    saved = true;
                }
            } catch (RuntimeException ignored) {
                // stop() throws if no frames were captured; the file is discarded below.
            }
            recorder.reset();
            recorder.release();
            recorder = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            projection.unregisterCallback(projectionCallback);
            projection.stop();
            projection = null;
        }
        if (outputPfd != null) {
            try { outputPfd.close(); } catch (Exception ignored) { }
            outputPfd = null;
        }

        if (outputUri != null) {
            if (saved) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(outputUri, values, null, null);
                showSavedNotification(getApplicationContext(), outputUri);
            } else {
                getContentResolver().delete(outputUri, null, null);
            }
            outputUri = null;
        }
        if (outputFile != null) {
            if (saved) {
                final Context app = getApplicationContext();
                MediaScannerConnection.scanFile(app, new String[]{outputFile.getAbsolutePath()},
                        new String[]{"video/mp4"}, (path, uri) -> {
                            if (uri != null) showSavedNotification(app, uri);
                        });
            } else {
                //noinspection ResultOfMethodCallIgnored
                outputFile.delete();
            }
            outputFile = null;
        }

        if (saved) {
            Toast.makeText(this, "Saved to Movies/ScreenRecorder", Toast.LENGTH_LONG).show();
        }
        isRecording = false;
        notifyState();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void fail(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        isRecording = false;
        stopRecording();
    }

    private void notifyState() {
        Runnable listener = stateListener;
        if (listener != null) handler.post(listener);
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Screen recording", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while the screen is being recorded");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), piFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ScreenRecordService.class).setAction(ACTION_STOP), piFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Recording screen")
                .setContentText("Tap Stop to finish and save the video")
                .setOngoing(true)
                .setContentIntent(openApp)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stop), "Stop", stop).build())
                .build();
    }

    /** "Recording saved" notification: tap to watch, or open the list of recordings. */
    @SuppressWarnings("deprecation")
    private static void showSavedNotification(Context context, Uri videoUri) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent watch = PendingIntent.getActivity(context, 2,
                new Intent(context, PlayerActivity.class).setData(videoUri), piFlags);
        PendingIntent all = PendingIntent.getActivity(context, 3,
                new Intent(context, RecordingsActivity.class), piFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);
        Notification n = builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Recording saved")
                .setContentText("Tap to watch")
                .setAutoCancel(true)
                .setContentIntent(watch)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(context, R.drawable.ic_video), "All recordings", all).build())
                .build();
        context.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID + 1, n);
    }

    @Override
    public void onDestroy() {
        if (isRecording) stopRecording();
        super.onDestroy();
    }
}
