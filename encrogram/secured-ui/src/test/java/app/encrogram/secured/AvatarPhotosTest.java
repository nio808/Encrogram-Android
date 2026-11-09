package app.encrogram.secured;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AvatarPhotosTest {
    @Test
    public void hashKeyIsContentBased() {
        final String a = AvatarPhotos.hashKey(new byte[]{1, 2, 3});
        assertEquals(a, AvatarPhotos.hashKey(new byte[]{1, 2, 3}));
        assertNotEquals(a, AvatarPhotos.hashKey(new byte[]{1, 2, 4}));
        assertEquals(64, a.length());
    }

    @Test
    public void emptyHasNoKey() {
        assertNull(AvatarPhotos.hashKey(new byte[0]));
        assertNull(AvatarPhotos.hashKey((byte[]) null));
    }
}
