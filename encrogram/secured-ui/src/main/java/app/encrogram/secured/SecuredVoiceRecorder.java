package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.RectF;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;

import app.encrogram.core.api.MediaInput;
import app.encrogram.core.api.PreparedMedia;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredMessage;
import app.encrogram.core.api.SecuredResult;
import app.encrogram.core.api.VoiceRecording;

/**
 * Voice messages, recorded like Telegram's: hold the mic to record, release to send, slide left to cancel, slide up
 * to lock (then the circle sends and "Cancel" drops it).
 *
 * The sound goes straight from {@link MediaRecorder} into a private file the engine owns
 * ({@link SecuredEngine#startVoiceRecording}): the UI never sees a path, and the engine closes and deletes the file on
 * every path once {@link MediaInput.Recorded} is handed to prepareMedia (or when the recording is closed here).
 * Settings match SimpleX's own Android recorder (AAC in MPEG-4, 16 kHz mono, 32 kbps), so voice works both ways.
 * Telegram's MediaController recording is never used (AGENTS.md rule 1).
 */
final class SecuredVoiceRecorder implements SecuredVoiceOverlay.Listener {

    static final int REQUEST_MIC = 0x5EC3;
    /** Shorter than this is a tap, not a message (Telegram drops these too). */
    private static final long MIN_MS = 700;
    /** The engine refuses more than 300 s or 3 MiB; stop a little before either. */
    private static final int MAX_MS = 299_000;
    private static final long MAX_BYTES = 3L * 1024 * 1024 - 64 * 1024;

    interface Host {
        BaseFragment fragment();
        SecuredEngine engine();
        long chatId();
        @Nullable Long replyToId();
        Theme.ResourcesProvider resources();
        SecuredVoiceOverlay overlay();
        /** The composer row and the mic button, to place the overlay. */
        View composerBar();
        View micButton();
        /** Called before the microphone opens: pause the voice player. */
        void onRecordingStarts();
        void onVoiceSent(SecuredMessage m);
        /** {@code e} null: the recorder itself failed. */
        void onVoiceFailed(@Nullable SecuredError e);
        void showNote(String text);
    }

    private enum State { IDLE, OPENING, RECORDING }

    private final Host host;
    private State state = State.IDLE;
    private int generation;
    private boolean locked;
    private boolean destroyed;
    private float downX, downY;
    private long startedAt;
    private float level;
    private VoiceRecording recording;
    private MediaRecorder recorder;
    private AudioFocusRequest focus;

    private final Runnable levelTick = new Runnable() {
        @Override
        public void run() {
            if (state != State.RECORDING || recorder == null) return;
            int amp = 0;
            try {
                amp = recorder.getMaxAmplitude();
            } catch (RuntimeException ignored) {
            }
            // Smoothed like Telegram's circle: quick to grow, slow to fall.
            final float target = Math.min(1f, amp / 12000f);
            level = target > level ? target : level * 0.8f + target * 0.2f;
            host.overlay().setLevel(level);
            AndroidUtilities.runOnUIThread(this, 60);
        }
    };

    SecuredVoiceRecorder(Host host) {
        this.host = host;
    }

    boolean isActive() {
        return state != State.IDLE;
    }

    // ---- the mic button ------------------------------------------------------------------------------------------

    /** The mic button's touch listener. */
    boolean onMicTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (state != State.IDLE || destroyed) return true;
                if (!hasMicPermission()) {
                    requestMicPermission();
                    return true;
                }
                downX = e.getRawX();
                downY = e.getRawY();
                begin();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (state == State.IDLE || locked) return true;
                final float dx = e.getRawX() - downX;
                final float dy = e.getRawY() - downY;
                if (dy < -dp(SecuredVoiceOverlay.LOCK_DP) && Math.abs(dy) > Math.abs(dx)) {
                    lock();
                } else if (dx < -dp(SecuredVoiceOverlay.CANCEL_DP)) {
                    finish(false);
                    haptic();
                } else if (Math.abs(dx) > Math.abs(dy)) {
                    host.overlay().setSlide(dx, 0);
                } else {
                    host.overlay().setSlide(0, dy);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (state == State.IDLE || locked) return true;
                final boolean longEnough = state == State.RECORDING && elapsed() >= MIN_MS;
                finish(longEnough && e.getActionMasked() == MotionEvent.ACTION_UP);
                if (!longEnough) host.showNote(host.fragment().getContext() != null ? host.fragment().getContext().getString(R.string.secvoice_hold) : null);
                return true;
        }
        return true;
    }

    @Override
    public void onLockedSend() {
        if (state == State.RECORDING) {
            finish(elapsed() >= MIN_MS);
        } else {
            finish(false);
        }
    }

    @Override
    public void onLockedCancel() {
        finish(false);
        haptic();
    }

    private void lock() {
        locked = true;
        host.overlay().setLocked();
        haptic();
    }

    // ---- recording -----------------------------------------------------------------------------------------------

    private void begin() {
        state = State.OPENING;
        locked = false;
        level = 0;
        final int gen = ++generation;
        host.onRecordingStarts();
        showOverlay();
        haptic();
        try {
            host.engine().startVoiceRecording(result -> {
                if (result instanceof SecuredResult.Ok) {
                    final VoiceRecording r = ((SecuredResult.Ok<VoiceRecording>) result).getValue();
                    if (gen != generation || state != State.OPENING || destroyed) {
                        // Released before the file was ready, or the screen closed: nothing was recorded.
                        r.close();
                        return;
                    }
                    startRecorder(r);
                } else if (gen == generation && state == State.OPENING) {
                    final SecuredError err = result instanceof SecuredResult.Err ? ((SecuredResult.Err) result).getError() : null;
                    reset();
                    host.onVoiceFailed(err);
                }
            });
        } catch (Throwable t) {
            reset();
            host.onVoiceFailed(null);
        }
    }

    private void startRecorder(VoiceRecording r) {
        recording = r;
        final Activity activity = host.fragment().getParentActivity();
        try {
            final MediaRecorder m = Build.VERSION.SDK_INT >= 31 && activity != null ? new MediaRecorder(activity) : new MediaRecorder();
            recorder = m;
            m.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (Build.VERSION.SDK_INT >= 30) {
                // Nothing else may capture the microphone while a Secured message records.
                m.setPrivacySensitive(true);
            }
            m.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            m.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            m.setAudioSamplingRate(16000);
            m.setAudioChannels(1);
            m.setAudioEncodingBitRate(32000);
            m.setMaxDuration(MAX_MS);
            m.setMaxFileSize(MAX_BYTES);
            m.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (recorder == mr) finish(true);
                    });
                }
            });
            m.setOnErrorListener((mr, what, extra) -> AndroidUtilities.runOnUIThread(() -> {
                if (recorder == mr) fail();
            }));
            m.setOutputFile(r.getFileDescriptor());
            m.prepare();
            requestFocus();
            m.start();
        } catch (Throwable t) {
            fail();
            return;
        }
        startedAt = SystemClock.elapsedRealtime();
        state = State.RECORDING;
        host.composerBar().setKeepScreenOn(true);
        AndroidUtilities.runOnUIThread(levelTick, 60);
    }

    /** Stops; sends what was recorded when {@code send}, otherwise the file is deleted. Idempotent. */
    private void finish(boolean send) {
        if (state == State.IDLE) return;
        final boolean wasRecording = state == State.RECORDING;
        final long durationMs = wasRecording ? elapsed() : 0;
        final MediaRecorder m = recorder;
        final VoiceRecording r = recording;
        generation++;
        reset();
        boolean ok = wasRecording && send;
        if (m != null) {
            try {
                m.stop();
            } catch (RuntimeException e) {
                // Stopped before any audio was written: there is nothing to send.
                ok = false;
            }
            try {
                m.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (r == null) return;
        if (!ok || destroyed) {
            r.close();
            return;
        }
        send(r, durationMs);
    }

    private void send(VoiceRecording r, long durationMs) {
        final SecuredEngine engine = host.engine();
        final long chatId = host.chatId();
        final Long replyTo = host.replyToId();
        try {
            // prepareMedia owns the recording from here: it closes and deletes it on every path.
            engine.prepareMedia(new MediaInput.Recorded(r, durationMs), result -> {
                if (!(result instanceof SecuredResult.Ok)) {
                    if (!destroyed) host.onVoiceFailed(result instanceof SecuredResult.Err ? ((SecuredResult.Err) result).getError() : null);
                    return;
                }
                final PreparedMedia prepared = ((SecuredResult.Ok<PreparedMedia>) result).getValue();
                if (destroyed) {
                    engine.discardMedia(prepared);
                    return;
                }
                engine.sendMedia(chatId, prepared, "", replyTo, sent -> {
                    if (destroyed) return;
                    if (sent instanceof SecuredResult.Ok) {
                        host.onVoiceSent(((SecuredResult.Ok<SecuredMessage>) sent).getValue());
                    } else {
                        host.onVoiceFailed(sent instanceof SecuredResult.Err ? ((SecuredResult.Err) sent).getError() : null);
                    }
                });
            });
        } catch (Throwable t) {
            r.close();
            host.onVoiceFailed(null);
        }
    }

    private void fail() {
        final boolean active = state != State.IDLE;
        finish(false);
        if (active) host.onVoiceFailed(null);
    }

    /** Back to idle: the overlay goes, focus and the screen lock are released. Does not touch the recorder. */
    private void reset() {
        state = State.IDLE;
        locked = false;
        recorder = null;
        recording = null;
        AndroidUtilities.cancelRunOnUIThread(levelTick);
        host.overlay().hide();
        host.composerBar().setKeepScreenOn(false);
        abandonFocus();
    }

    /** The screen paused (Home, lock, a call): a recording in progress is stopped and deleted, never sent unseen. */
    void onPause() {
        if (state == State.IDLE) return;
        final boolean had = state == State.RECORDING && elapsed() >= MIN_MS;
        finish(false);
        if (had) host.showNote(host.fragment().getContext() != null ? host.fragment().getContext().getString(R.string.secvoice_stopped) : null);
    }

    void destroy() {
        destroyed = true;
        finish(false);
    }

    private long elapsed() {
        return SystemClock.elapsedRealtime() - startedAt;
    }

    private void showOverlay() {
        final View bar = host.composerBar();
        final View mic = host.micButton();
        final SecuredVoiceOverlay overlay = host.overlay();
        final int[] o = new int[2];
        final int[] b = new int[2];
        final int[] m = new int[2];
        // The overlay fills its parent (the chat screen's root), so the parent's position is the overlay's.
        ((View) overlay.getParent()).getLocationInWindow(o);
        bar.getLocationInWindow(b);
        mic.getLocationInWindow(m);
        final RectF rect = new RectF(b[0] - o[0] + dp(8), b[1] - o[1] + dp(6), b[0] - o[0] + bar.getWidth() - dp(8), b[1] - o[1] + bar.getHeight() - dp(8));
        overlay.show(rect, m[0] - o[0] + mic.getWidth() / 2f, m[1] - o[1] + mic.getHeight() / 2f);
    }

    private void haptic() {
        try {
            host.micButton().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
        } catch (Throwable ignored) {
        }
    }

    // ---- microphone permission -----------------------------------------------------------------------------------

    private boolean hasMicPermission() {
        final Activity a = host.fragment().getParentActivity();
        return a != null && a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMicPermission() {
        final Activity a = host.fragment().getParentActivity();
        if (a == null) return;
        try {
            a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
        } catch (Throwable t) {
            showMicSettings();
        }
    }

    /** From the fragment's onRequestPermissionsResultFragment. True when it was ours. */
    boolean onPermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_MIC) return false;
        final boolean granted = grantResults != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        final Context c = host.fragment().getContext();
        if (granted) {
            if (c != null) host.showNote(c.getString(R.string.secvoice_hold));
        } else {
            showMicSettings();
        }
        return true;
    }

    private void showMicSettings() {
        final Activity a = host.fragment().getParentActivity();
        if (a == null) return;
        final AlertDialog d = new AlertDialog.Builder(a, host.resources())
                .setTopAnimation(org.telegram.messenger.R.raw.permission_request_microphone, AlertsCreator.PERMISSIONS_REQUEST_TOP_ICON_SIZE, false,
                        Theme.getColor(Theme.key_dialogTopBackground, host.resources()))
                .setMessage(a.getString(R.string.secvoice_mic_needed))
                .setPositiveButton(a.getString(R.string.secvoice_open_settings), (dialog, which) -> {
                    try {
                        final Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        i.setData(Uri.parse("package:" + a.getPackageName()));
                        a.startActivity(i);
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton(a.getString(R.string.secvoice_not_now), null)
                .create();
        Kit.show(host.fragment(), d);
    }

    // ---- audio focus: other apps pause while the microphone records -------------------------------------------------

    private void requestFocus() {
        final AudioManager am = audioManager();
        if (am == null) return;
        if (focus == null) {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .build();
        }
        am.requestAudioFocus(focus);
    }

    private void abandonFocus() {
        if (focus == null) return;
        final AudioManager am = audioManager();
        if (am != null) am.abandonAudioFocusRequest(focus);
    }

    @Nullable
    private AudioManager audioManager() {
        final Activity a = host.fragment().getParentActivity();
        return a != null ? (AudioManager) a.getSystemService(Context.AUDIO_SERVICE) : null;
    }
}
