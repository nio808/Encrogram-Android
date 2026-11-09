package app.encrogram.secured;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.telegram.messenger.ApplicationLoader;

import app.encrogram.core.api.MediaPlayback;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredMessage;
import app.encrogram.core.api.SecuredResult;

/**
 * The chat screen's one voice player (task MU4, reuse level 4: new). Plays a Secured voice message from the engine's
 * {@link MediaPlayback} through a private {@link MediaPlayer}; never Telegram's MediaController (the wall, AGENTS.md
 * rule 1). One message at a time. Main thread only.
 */
final class SecuredAudioPlayer {

    interface Listener {
        /** Main thread: redraw that cell (about 10 a second while playing). */
        void onAudioChanged(long chatId, long messageId);

        /** Could not open or play; null = the player itself failed. */
        default void onAudioFailed(@Nullable SecuredError error) {
        }
    }

    private static final long TICK_MS = 100;

    private final SecuredEngine engine;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Bumped on every switch and release, so an open result that arrives late can tell it is stale. */
    private int generation;
    private boolean hasCurrent;
    private long curChat;
    private long curMessage;
    private float pendingFraction = -1f;

    private MediaPlayback playback;
    private ReaderDataSource dataSource;
    private MediaPlayer player;
    private boolean prepared;
    private boolean playing;

    private AudioFocusRequest focusRequest;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!playing || !hasCurrent) return;
            listener.onAudioChanged(curChat, curMessage);
            handler.postDelayed(this, TICK_MS);
        }
    };

    SecuredAudioPlayer(SecuredEngine engine, Listener listener) {
        this.engine = engine;
        this.listener = listener;
    }

    // ---- public API ------------------------------------------------------------------------------------------

    void toggle(SecuredMessage m) {
        if (m == null) return;
        if (isCurrent(m)) {
            if (!prepared) {
                // Tapped again while still opening: cancel.
                release();
            } else if (playing) {
                pause();
            } else {
                startPlaying();
            }
            return;
        }
        open(m, -1f);
    }

    void seek(SecuredMessage m, float fraction) {
        if (m == null) return;
        final float f = Math.max(0f, Math.min(1f, fraction));
        if (!isCurrent(m)) {
            open(m, f);
        } else if (prepared && player != null) {
            final int duration = duration();
            if (duration > 0) {
                try {
                    player.seekTo((int) (f * duration));
                } catch (RuntimeException e) {
                    fail(null);
                    return;
                }
            }
            listener.onAudioChanged(curChat, curMessage);
        } else {
            pendingFraction = f;
        }
    }

    boolean isPlaying(long chatId, long messageId) {
        return playing && isCurrent(chatId, messageId);
    }

    float progress(long chatId, long messageId) {
        if (!prepared || player == null || !isCurrent(chatId, messageId)) return 0f;
        final int duration = duration();
        if (duration <= 0) return 0f;
        try {
            return Math.max(0f, Math.min(1f, player.getCurrentPosition() / (float) duration));
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    int positionSeconds(long chatId, long messageId) {
        if (!prepared || player == null || !isCurrent(chatId, messageId)) return 0;
        try {
            return Math.max(0, player.getCurrentPosition() / 1000);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Pause only; the message stays loaded so play resumes from here. */
    void pause() {
        if (!playing) return;
        playing = false;
        handler.removeCallbacks(tick);
        try {
            if (player != null) player.pause();
        } catch (RuntimeException ignored) {
            // A dead player is cleaned up on the next release.
        }
        abandonFocus();
        notifyCurrent();
    }

    /** Stops everything and frees the playback (which deletes any private decrypted copy). Idempotent. */
    void release() {
        final boolean had = hasCurrent;
        final long chat = curChat;
        final long message = curMessage;
        generation++;
        handler.removeCallbacks(tick);
        playing = false;
        prepared = false;
        pendingFraction = -1f;
        hasCurrent = false;
        final MediaPlayer p = player;
        final ReaderDataSource ds = dataSource;
        final MediaPlayback pb = playback;
        player = null;
        dataSource = null;
        playback = null;
        if (p != null) {
            try {
                p.setOnPreparedListener(null);
                p.setOnCompletionListener(null);
                p.setOnErrorListener(null);
                p.stop();
            } catch (RuntimeException ignored) {
                // Not prepared yet: stop() throws, release() below is still right.
            }
            try {
                p.release();
            } catch (RuntimeException ignored) {
            }
        }
        if (ds != null) ds.close();
        if (pb != null) {
            try {
                engine.releasePlayback(pb);
            } catch (RuntimeException ignored) {
            }
        }
        abandonFocus();
        if (had) listener.onAudioChanged(chat, message);
    }

    // ---- opening and playing ---------------------------------------------------------------------------------

    private void open(SecuredMessage m, float fraction) {
        release();
        hasCurrent = true;
        curChat = m.getChatId();
        curMessage = m.getMessageId();
        pendingFraction = fraction;
        final int gen = generation;
        notifyCurrent();
        try {
            engine.openMediaForPlayback(curChat, curMessage, result -> {
                if (result instanceof SecuredResult.Ok) {
                    final MediaPlayback pb = ((SecuredResult.Ok<MediaPlayback>) result).getValue();
                    if (gen != generation) {
                        // Another toggle or a release came first: this one is stale.
                        engine.releasePlayback(pb);
                        return;
                    }
                    prepare(pb);
                } else if (gen == generation) {
                    final SecuredError e = result instanceof SecuredResult.Err ? ((SecuredResult.Err) result).getError() : null;
                    fail(e);
                }
            });
        } catch (Throwable t) {
            if (gen == generation) fail(null);
        }
    }

    private void prepare(MediaPlayback pb) {
        playback = pb;
        try {
            dataSource = new ReaderDataSource(pb.getReader());
            final MediaPlayer p = new MediaPlayer();
            player = p;
            p.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            p.setOnPreparedListener(mp -> onPrepared());
            p.setOnCompletionListener(mp -> onCompleted());
            p.setOnErrorListener((mp, what, extra) -> {
                fail(null);
                return true;
            });
            p.setDataSource(dataSource);
            p.prepareAsync();
        } catch (Throwable t) {
            fail(null);
        }
    }

    private void onPrepared() {
        if (!hasCurrent || player == null) return;
        prepared = true;
        final float f = pendingFraction;
        pendingFraction = -1f;
        final int duration = duration();
        if (f > 0f && duration > 0) {
            try {
                player.seekTo((int) (f * duration));
            } catch (RuntimeException e) {
                fail(null);
                return;
            }
        }
        startPlaying();
    }

    private void startPlaying() {
        if (!prepared || player == null) return;
        requestFocus();
        try {
            player.start();
        } catch (RuntimeException e) {
            fail(null);
            return;
        }
        playing = true;
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
        notifyCurrent();
    }

    private void onCompleted() {
        // Back to the start: releasing makes progress and position read 0 and frees the file.
        release();
    }

    private void fail(@Nullable SecuredError error) {
        release();
        listener.onAudioFailed(error);
    }

    // ---- audio focus -----------------------------------------------------------------------------------------

    private void requestFocus() {
        final AudioManager am = audioManager();
        if (am == null) return;
        if (focusRequest == null) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            pause();
                        }
                    }, handler)
                    .build();
        }
        am.requestAudioFocus(focusRequest);
    }

    private void abandonFocus() {
        if (focusRequest == null) return;
        final AudioManager am = audioManager();
        if (am != null) am.abandonAudioFocusRequest(focusRequest);
    }

    @Nullable
    private static AudioManager audioManager() {
        final Context c = ApplicationLoader.applicationContext;
        return c != null ? (AudioManager) c.getSystemService(Context.AUDIO_SERVICE) : null;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private int duration() {
        try {
            return player != null && prepared ? player.getDuration() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private boolean isCurrent(SecuredMessage m) {
        return isCurrent(m.getChatId(), m.getMessageId());
    }

    private boolean isCurrent(long chatId, long messageId) {
        return hasCurrent && curChat == chatId && curMessage == messageId;
    }

    private void notifyCurrent() {
        if (hasCurrent) listener.onAudioChanged(curChat, curMessage);
    }
}
