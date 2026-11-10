package app.encrogram.android;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.Arrays;
import java.util.TreeSet;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class BrandStringsTest {

    /** Every override in res/values/strings.xml must be cut from Telegram's language packs, and nothing else. */
    @Test public void keysMatchOurStringsFile() throws Exception {
        final NodeList strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new File("src/main/res/values/strings.xml")).getElementsByTagName("string");
        final TreeSet<String> inFile = new TreeSet<>();
        for (int i = 0; i < strings.getLength(); i++) inFile.add(((Element) strings.item(i)).getAttribute("name"));
        assertEquals(inFile, new TreeSet<>(Arrays.asList(BrandStrings.KEYS)));
    }

    @Test public void cutsOneLineAndMultiLineEntries() {
        final String pack = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                + "<string name=\"AppName\">Telegram</string>\n"
                + "<string name=\"OK\">OK</string>\n"
                + "<string name=\"Page1Message\">The world\'s fastest\nmessaging app.</string>\n"
                + "<string name=\"AppNameX\">kept</string>\n"
                + "<string name=\"Cancel\">Cancel</string>\n"
                + "</resources>";
        assertEquals("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n"
                + "<string name=\"OK\">OK</string>\n"
                + "<string name=\"AppNameX\">kept</string>\n"
                + "<string name=\"Cancel\">Cancel</string>\n"
                + "</resources>", BrandStrings.strip(pack));
    }

    @Test public void cutsTheLastEntryAndLeavesOthersAlone() {
        assertEquals("<resources>\n</resources>",
                BrandStrings.strip("<resources>\n<string name=\"AppNameBeta\">Telegram Beta</string>\n</resources>"));
        final String none = "<resources>\n<string name=\"OK\">OK</string>\n</resources>";
        assertEquals(none, BrandStrings.strip(none));
    }

    @Test public void ignoresTheKeyInsideAnotherValue() {
        final String pack = "<resources>\n<string name=\"Raw\"><![CDATA[x <string name=\"AppName\">y]]></string>\n</resources>";
        assertEquals(pack, BrandStrings.strip(pack));
    }

    @Test public void leavesABrokenEntryAlone() {
        final String pack = "<resources>\n<string name=\"OK\">OK</string>\n<string name=\"AppName\">Telegram";
        assertEquals(pack, BrandStrings.strip(pack));
    }
}
