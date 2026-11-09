package app.encrogram.secured;

import android.media.MediaDataSource;

import java.io.IOException;

import app.encrogram.core.api.MediaReader;

/**
 * Android's {@link MediaDataSource} over the engine's {@link MediaReader} (task MU0, reuse level 4: a platform
 * adapter). {@link android.media.MediaPlayer} calls it on its own thread, which the reader allows (never main).
 * Closing this does NOT close the reader: the owner releases the {@link app.encrogram.core.api.MediaPlayback}
 * (SecuredEngine.releasePlayback), which closes the reader and deletes any private decrypted copy.
 */
final class ReaderDataSource extends MediaDataSource {

    private final MediaReader reader;
    private volatile boolean closed;

    ReaderDataSource(MediaReader reader) {
        this.reader = reader;
    }

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (closed) return -1;
        if (size == 0) return 0;
        try {
            return reader.readAt(position, buffer, offset, size);
        } catch (RuntimeException e) {
            // A released playback (engine stop, message deleted) reads as the end; the player then reports an error.
            throw new IOException("closed");
        }
    }

    @Override
    public long getSize() {
        if (closed) return -1;
        try {
            return reader.size();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
