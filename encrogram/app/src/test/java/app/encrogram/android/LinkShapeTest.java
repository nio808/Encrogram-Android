package app.encrogram.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;

import org.junit.Test;

public class LinkShapeTest {
    private static final String HOST = "encrogram.example.com";

    /** Splits like android.net.Uri does (decoded path, null user-info, -1 for no port). */
    private static boolean ok(String link) {
        try {
            final URI u = new URI(link);
            return LinkShape.isValid(u.getScheme(), u.getHost(), u.getUserInfo(), u.getPort(), u.getPath(), link.length(), HOST);
        } catch (Exception e) {
            return false;
        }
    }

    @Test public void acceptsOurThreePaths() {
        assertTrue(ok("https://encrogram.example.com/i#abc"));
        assertTrue(ok("https://encrogram.example.com/a#abc"));
        assertTrue(ok("https://ENCROGRAM.example.com/g#abc"));
        assertTrue(ok("HTTPS://encrogram.example.com:443/i#abc"));
    }

    @Test public void rejectsWrongHost() {
        assertFalse(ok("https://evil.example.com/i#abc"));
        assertFalse(ok("https://encrogram.example.com.evil.com/i#abc"));
        assertFalse(ok("https://evilencrogram.example.com/i#abc"));
    }

    @Test public void rejectsWrongSchemeAndPort() {
        assertFalse(ok("http://encrogram.example.com/i#abc"));
        assertFalse(ok("ftp://encrogram.example.com/i#abc"));
        assertFalse(ok("https://encrogram.example.com:8443/i#abc"));
    }

    @Test public void rejectsUserInfo() {
        assertFalse(ok("https://user@encrogram.example.com/i#abc"));
        assertFalse(ok("https://encrogram.example.com@evil.com/i#abc"));
    }

    @Test public void rejectsExtraOrWrongPath() {
        assertFalse(ok("https://encrogram.example.com/i/extra#abc"));
        assertFalse(ok("https://encrogram.example.com/i/#abc"));
        assertFalse(ok("https://encrogram.example.com/x#abc"));
        assertFalse(ok("https://encrogram.example.com/#abc"));
        assertFalse(ok("https://encrogram.example.com//i#abc"));
    }

    @Test public void rejectsEncodedCharactersThatChangeThePath() {
        assertFalse(ok("https://encrogram.example.com/i%2f#abc"));
        assertFalse(ok("https://encrogram.example.com/i%2e#abc"));
        assertFalse(ok("https://encrogram.example.com/i%00#abc"));
        assertFalse(ok("https://encrogram.example.com/%2e%2e/i#abc"));
    }

    @Test public void queryKeysAreNotThisGatesJob() {
        // The shape gate ignores query and fragment; repeated keys are refused later by the engine's checkLink.
        assertTrue(ok("https://encrogram.example.com/i?x=1&x=2#abc"));
    }

    @Test public void rejectsTooLong() {
        final StringBuilder b = new StringBuilder("https://encrogram.example.com/i#");
        while (b.length() <= LinkShape.MAX_LINK_LENGTH) b.append('a');
        assertFalse(ok(b.toString()));
        assertTrue(ok(b.substring(0, LinkShape.MAX_LINK_LENGTH)));
    }

    @Test public void nullHostIsRejected() {
        assertFalse(LinkShape.isValid("https", null, null, -1, "/i", 20, HOST));
    }
}
