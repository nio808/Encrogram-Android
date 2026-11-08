package app.encrogram.secured;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.URLSpan;

import org.junit.Test;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.FormattedDateSpan;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.URLSpanBotCommand;
import org.telegram.ui.Components.URLSpanBrowser;
import org.telegram.ui.Components.URLSpanMono;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Components.URLSpanReplacement;
import org.telegram.ui.Components.URLSpanUserMention;

/**
 * Gaps audit L4: which spans may stay in a Secured bubble. Plain JVM (junit only): the rule looks only at a span's class
 * and address, so the test needs class literals, never span instances (Android's span constructors are stubs here).
 * Run: {@code ./gradlew :encrogram-secured-ui:testDebugUnitTest --tests '*SecuredLinkSpansTest'}.
 */
public class SecuredLinkSpansTest {

    private static boolean allowed(Class<?> type, String url) {
        return SecuredMessageMapper.LinkSpans.allowed(type, url);
    }

    /**
     * The spans Telegram puts on "@durov #tag $USD /start https://example.com" when it re-parses a pending or failed
     * bubble (MessageObject.applyEntities, useManualParse): addUrlsByPattern and AndroidUtilities.addLinksSafe.
     */
    @Test
    public void onlyTheHttpsLinkSurvivesTelegramsReparse() {
        assertFalse("@mention", allowed(URLSpanNoUnderline.class, "@durov"));
        assertFalse("#hashtag", allowed(URLSpanNoUnderline.class, "#tag"));
        assertFalse("$cashtag", allowed(URLSpanNoUnderline.class, "$USD"));
        assertFalse("/command", allowed(URLSpanBotCommand.class, "/start"));
        assertTrue("https link from addLinksSafe", allowed(URLSpan.class, SecuredMessageMapper.LINK_SAMPLE_URL));
    }

    /** A sent or incoming bubble: our TL_messageEntityUrl becomes Telegram's URLSpanBrowser. */
    @Test
    public void ourUrlEntityStays() {
        assertTrue(allowed(URLSpanBrowser.class, "https://example.com/x"));
        assertTrue(allowed(URLSpanBrowser.class, "http://example.com"));
        assertTrue("scheme case", allowed(URLSpan.class, "HTTPS://EXAMPLE.COM"));
    }

    @Test
    public void nonWebAddressesAreRefusedEvenOnAllowedClasses() {
        assertFalse(allowed(URLSpan.class, "tg://resolve?domain=durov"));
        assertFalse(allowed(URLSpan.class, "tonsite://example.ton"));
        assertFalse(allowed(URLSpanBrowser.class, "tonsite://example.ton"));
        assertFalse(allowed(URLSpan.class, "mailto:someone@example.com"));
        assertFalse(allowed(URLSpan.class, "tel:+15550100"));
        assertFalse(allowed(URLSpanBrowser.class, "javascript:alert(1)"));
        assertFalse(allowed(URLSpan.class, "example.com"));
        assertFalse(allowed(URLSpan.class, ""));
        assertFalse(allowed(URLSpan.class, null));
    }

    /** Telegram-only span types are refused whatever address they carry. */
    @Test
    public void telegramLinkTypesAreRefusedWhateverTheAddress() {
        final String web = "https://example.com";
        assertFalse(allowed(URLSpanNoUnderline.class, web));
        assertFalse(allowed(URLSpanUserMention.class, web));
        assertFalse(allowed(URLSpanBotCommand.class, web));
        assertFalse("text url shows one address and opens another", allowed(URLSpanReplacement.class, web));
        assertFalse(allowed(FormattedDateSpan.class, web));
        assertFalse("tap to copy", allowed(URLSpanMono.class, null));
        assertFalse("Telegram emoji documents", allowed(AnimatedEmojiSpan.class, null));
        assertFalse("any other clickable span", allowed(ClickableSpan.class, null));
        assertFalse("a subclass of URLSpan is not URLSpan", allowed(SneakyUrlSpan.class, web));
        assertFalse(allowed(null, web));
    }

    /** Styling stays: our italic labels (TextStyleSpan), colours, bold. */
    @Test
    public void nonClickableSpansStay() {
        assertTrue(allowed(TextStyleSpan.class, null));
        assertTrue(allowed(ForegroundColorSpan.class, null));
        assertTrue(allowed(StyleSpan.class, null));
    }

    @Test
    public void webUrlCheck() {
        assertTrue(SecuredMessageMapper.LinkSpans.isWebUrl("https://a.b"));
        assertTrue(SecuredMessageMapper.LinkSpans.isWebUrl("Http://a.b"));
        assertFalse(SecuredMessageMapper.LinkSpans.isWebUrl("https:/a.b"));
        assertFalse(SecuredMessageMapper.LinkSpans.isWebUrl("ftp://a.b"));
        assertFalse(SecuredMessageMapper.LinkSpans.isWebUrl(" https://a.b"));
    }

    /** Never instantiated: only its class is checked. */
    private static final class SneakyUrlSpan extends URLSpan {
        SneakyUrlSpan() {
            super("https://example.com");
        }
    }
}
