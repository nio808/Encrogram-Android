package app.encrogram.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Arrays;

import org.junit.Test;

public class EngineBootstrapTest {
    @Test public void hostOfPlainAddresses() {
        assertEquals("relay.example.com", EngineBootstrap.hostOf("smp://FP@relay.example.com"));
        assertEquals("relay.example.com", EngineBootstrap.hostOf("smp://FP@Relay.Example.com:5223"));
        assertEquals("relay.example.com", EngineBootstrap.hostOf("xftp://FP:pw@relay.example.com:443"));
    }

    @Test public void lastAtSignWins() {
        assertEquals("good.example.com", EngineBootstrap.hostOf("smp://evil.com@FP@good.example.com:5223"));
    }

    @Test public void malformedGivesNoHost() {
        assertNull(EngineBootstrap.hostOf("smp://FP@"));
        assertNull(EngineBootstrap.hostOf("smp://FP@bad host"));
        assertNull(EngineBootstrap.hostOf(""));
    }

    @Test public void splitServersHandlesSeparators() {
        assertEquals(Arrays.asList("a", "b", "c", "d"), EngineBootstrap.splitServers("a, b;c\nd"));
        assertEquals(0, EngineBootstrap.splitServers(null).size());
        assertEquals(0, EngineBootstrap.splitServers("  ,; ").size());
    }
}
