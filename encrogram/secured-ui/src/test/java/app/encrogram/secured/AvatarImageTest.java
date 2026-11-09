package app.encrogram.secured;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AvatarImageTest {
    @Test
    public void refusesHugeOrEmpty() {
        assertTrue(AvatarImage.sizeOk(4000, 3000));
        assertFalse(AvatarImage.sizeOk(0, 100));
        assertFalse(AvatarImage.sizeOk(20000, 100));
        assertFalse(AvatarImage.sizeOk(10000, 10000));
    }

    @Test
    public void sampleKeepsShortSideAboveTarget() {
        assertEquals(1, AvatarImage.sampleSize(200, 300, 192));
        assertEquals(4, AvatarImage.sampleSize(1000, 3000, 192));
        assertEquals(1, AvatarImage.sampleSize(100, 100, 192));
    }

    @Test
    public void limitIsInclusive() {
        assertTrue(AvatarImage.fits(9000));
        assertFalse(AvatarImage.fits(9001));
        assertFalse(AvatarImage.fits(0));
    }
}
