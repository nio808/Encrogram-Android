package app.encrogram.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.SparseArray;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.utils.localization.Localization;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Keeps our overrides of Telegram strings (res/values/strings.xml: AppName, the intro pages, the icon names) from being
 * replaced by Telegram's cloud language pack.
 *
 * Telegram's LocaleController.getString asks the downloaded language pack first (BuildVars.USE_CLOUD_STRINGS) and the
 * strings bundled in the APK second. The pack has AppName = "Telegram", so once Telegram's network had run once, every
 * alert titled with AppName said "Telegram". The hook budget is spent (6 of 6), so this works from our Application:
 * <ol>
 *   <li>At start, before Telegram reads them, our keys are cut from the packs Telegram cached in files/
 *       (remote_*.xml and unofficial_*.xml, written only by LocaleController.saveRemoteLocaleStrings).</li>
 *   <li>When a pack arrives while the app runs, Telegram swaps it in and posts reloadInterface in the same UI-thread step.
 *       We are the first observer (registered at app start), so our keys are dropped from the loaded pack before any
 *       screen redraws, and getString falls through to our bundled values.</li>
 * </ol>
 * Gap: picking a language in Telegram's settings while offline loads that language's cached pack and posts nothing until
 * the download finishes, so AppName can read "Telegram" until the next pack or the next start.
 *
 * Nothing here touches Secured data. LocaleController is kept whole by TMessagesProj/proguard-rules.pro; Localization is
 * not, so its one map field is found by type, not by name.
 */
final class BrandStrings {

    private static final String TAG = "EncrogramBrand";

    /** Every name in res/values/strings.xml. BrandStringsTest keeps the two lists equal. */
    static final String[] KEYS = {
            "AppName", "AppNameBeta",
            "Page1Title", "Page1Message", "Page2Title", "Page2Message", "Page3Title", "Page3Message",
            "Page4Title", "Page4Message", "Page5Title", "Page5Message", "Page6Title", "Page6Message",
            "AppIconDefault", "AppIconVintage", "AppIconAqua", "AppIconPremium", "AppIconTurbo", "AppIconNox",
    };

    private BrandStrings() {}

    /**
     * Step 1. Call before Telegram's LocaleController exists (before ApplicationLoader.onCreate). The app runs in one
     * process and no Telegram code has run yet, so nothing else writes these files meanwhile. A pack is about 1 MB, so
     * one already checked (same size and time) is not read again.
     */
    static void stripCachedPacks() {
        try {
            final File[] packs = ApplicationLoader.getFilesDirFixed().listFiles((dir, name) ->
                    (name.startsWith("remote_") || name.startsWith("unofficial_")) && name.endsWith(".xml"));
            if (packs == null || packs.length == 0) return;
            final SharedPreferences checked =
                    ApplicationLoader.applicationContext.getSharedPreferences("encrogram_brand_strings", Context.MODE_PRIVATE);
            for (File pack : packs) {
                if (stamp(pack).equals(checked.getString(pack.getName(), null))) continue;
                final String xml = new String(Files.readAllBytes(pack.toPath()), StandardCharsets.UTF_8);
                final String stripped = strip(xml);
                if (!stripped.equals(xml)) {
                    // Write a sibling and rename over the pack, so a crash mid-write never leaves Telegram a broken file.
                    final File tmp = new File(pack.getPath() + ".encrogram-tmp");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(stripped.getBytes(StandardCharsets.UTF_8));
                        out.getFD().sync();
                    }
                    if (!tmp.renameTo(pack)) {
                        tmp.delete();
                        continue;
                    }
                    if (BuildConfig.DEBUG) Log.d(TAG, "cut our keys from " + pack.getName());
                }
                checked.edit().putString(pack.getName(), stamp(pack)).apply();
            }
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.w(TAG, "cached packs: " + e.getClass().getSimpleName());
        }
    }

    /** Size, time and our key list, so an app update that adds a key checks every pack again. */
    private static String stamp(File file) {
        return file.length() + ":" + file.lastModified() + ":" + java.util.Arrays.hashCode(KEYS);
    }

    /**
     * Removes our keys' entries from a pack file's text. Telegram writes one {@code <string name="KEY">VALUE</string>}
     * per entry, each starting on its own line; a value may itself span lines. Returns the input unchanged if an entry
     * looks broken.
     */
    static String strip(String xml) {
        String out = xml;
        for (String key : KEYS) {
            final String open = "<string name=\"" + key + "\">";
            int from = 0;
            int start;
            while ((start = out.indexOf(open, from)) >= 0) {
                if (start > 0 && out.charAt(start - 1) != '\n') {
                    from = start + 1; // inside another entry's value, not an entry of its own
                    continue;
                }
                int end = out.indexOf("</string>", start + open.length());
                if (end < 0) return xml;
                end += "</string>".length();
                if (end < out.length() && out.charAt(end) == '\n') end++;
                out = out.substring(0, start) + out.substring(end);
                from = start;
            }
        }
        return out;
    }

    /** Step 2. Call on the UI thread once ApplicationLoader.onCreate has run. */
    static void install() {
        NotificationCenter.getGlobalInstance()
                .createObserversGroup((id, account, args) -> stripLoadedPack())
                .add(NotificationCenter.reloadInterface);
    }

    private static void stripLoadedPack() {
        try {
            // Never create LocaleController here: other screens post reloadInterface too, and building it early would
            // queue Telegram's language-list request before its native code is loaded.
            final Field instanceField = LocaleController.class.getDeclaredField("Instance");
            instanceField.setAccessible(true);
            final Object controller = instanceField.get(null);
            if (controller == null) return;

            final Field externalField = LocaleController.class.getDeclaredField("localizationExternal");
            externalField.setAccessible(true);
            final Localization pack = (Localization) externalField.get(controller);
            if (pack == null || !hasAnyKey(pack)) return;

            // Strip a copy and swap it in, as Telegram itself does, instead of editing the map other threads are reading.
            final Localization copy = new Localization.Builder().addLocalization(pack).build();
            final SparseArray<?> map = mapOf(copy);
            for (String key : KEYS) map.remove(key.hashCode());
            externalField.set(controller, copy);
            if (BuildConfig.DEBUG) Log.d(TAG, "cut our keys from the loaded pack");
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.w(TAG, "loaded pack: " + e.getClass().getSimpleName());
        }
    }

    private static boolean hasAnyKey(Localization pack) {
        for (String key : KEYS) {
            if (pack.getByResName(key) != null) return true;
        }
        return false;
    }

    private static SparseArray<?> mapOf(Localization localization) throws ReflectiveOperationException {
        for (Field field : Localization.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == SparseArray.class) {
                field.setAccessible(true);
                return (SparseArray<?>) field.get(localization);
            }
        }
        throw new NoSuchFieldException("Localization map");
    }
}
