package br.appdaqui.designpreview;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

final class NativeMic {
    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private final Context context;
    private final File recording;
    private MediaRecorder recorder;
    private boolean finished, paused;

    NativeMic(Context context) {
        this.context = context;
        recording = new File(context.getCacheDir(), "allembra-recording.m4a");
        recording.delete();
    }

    synchronized void start(Runnable onLimit) throws IOException {
        discard();
        MediaRecorder next = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(context) : new MediaRecorder();
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC);
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            next.setAudioEncodingBitRate(96000);
            next.setAudioSamplingRate(44100);
            next.setOutputFile(recording.getAbsolutePath());
            next.setMaxDuration(60000);
            next.setMaxFileSize(MAX_BYTES);
            next.setOnInfoListener((sender, what, extra) -> {
                synchronized (this) {
                    if (sender == recorder && (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                            || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)) onLimit.run();
                }
            });
            next.prepare();
            next.start();
            recorder = next;
        } catch (IOException | RuntimeException failure) {
            next.release();
            recording.delete();
            throw failure;
        }
    }

    synchronized boolean setPaused(boolean next) {
        if (recorder == null) return false;
        if (next == paused) return true;
        try {
            if (next) recorder.pause(); else recorder.resume();
            paused = next;
            return true;
        } catch (RuntimeException notReady) { return false; }
    }

    synchronized int level() {
        try { return recorder == null || paused ? 0 : recorder.getMaxAmplitude(); }
        catch (IllegalStateException unavailable) { return 0; }
    }

    synchronized boolean finish() {
        if (recorder == null) return false;
        try {
            recorder.stop();
        } catch (RuntimeException tooShort) {
            recording.delete();
        } finally {
            recorder.release();
            recorder = null;
            paused = false;
        }
        finished = recording.length() > 0 && recording.length() <= MAX_BYTES;
        if (!finished) recording.delete();
        return finished;
    }

    synchronized String take() {
        if (!finished) return "";
        finished = false;
        try (FileInputStream input = new FileInputStream(recording);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (bytes.size() > MAX_BYTES - count) return "";
                bytes.write(buffer, 0, count);
            }
            return Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        } catch (IOException failure) { return ""; }
        finally { recording.delete(); }
    }

    synchronized void discard() {
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) {}
            recorder.release();
            recorder = null;
            paused = false;
        }
        finished = false;
        paused = false;
        recording.delete();
    }
}
