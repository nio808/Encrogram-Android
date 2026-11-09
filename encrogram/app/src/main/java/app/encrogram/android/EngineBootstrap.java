package app.encrogram.android;

import android.content.Context;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.encrogram.core.api.RelayConfig;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredEngines;

/**
 * The one place where the app builds the Secured engine. Reads our relay addresses from BuildConfig
 * (written from encrogram/local.properties) and derives the allowed relay hosts from them. The backup base URL
 * comes from encrogram/secured.properties (SECURED_BACKUP_BASE_URL).
 * If the factory in :encrogram-core changes shape, only this class needs adjusting.
 */
final class EngineBootstrap {

    private EngineBootstrap() {}

    static SecuredEngine create(Context context) {
        final List<String> smp = splitServers(BuildConfig.SECURED_SMP_SERVER);
        final List<String> xftp = splitServers(BuildConfig.SECURED_XFTP_SERVER);
        final Set<String> hosts = new LinkedHashSet<>();
        for (String s : smp) addHost(hosts, s);
        for (String s : xftp) addHost(hosts, s);
        final String backupUrl = BuildConfig.SECURED_BACKUP_URL.isEmpty() ? null : BuildConfig.SECURED_BACKUP_URL;
        return SecuredEngines.create(context.getApplicationContext(), new RelayConfig(smp, xftp, new ArrayList<>(hosts), backupUrl));
    }

    /** smp://fingerprint[:password]@host[:port] and xftp://... : the host after the last '@'. */
    private static void addHost(Set<String> hosts, String server) {
        final String host = hostOf(server);
        if (host != null) hosts.add(host);
    }

    /** Lower-cased host of an smp:// or xftp:// address, or null when malformed (the engine then fails closed). */
    static String hostOf(String server) {
        String rest = server;
        final int scheme = rest.indexOf("://");
        if (scheme >= 0) rest = rest.substring(scheme + 3);
        final int at = rest.lastIndexOf('@');
        if (at >= 0) rest = rest.substring(at + 1);
        // Several hosts for one server may be comma separated inside the address; split_servers already handled that.
        try {
            final String host = new URI("smp://" + rest).getHost();
            if (host != null && !host.isEmpty()) return host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception ignored) {
            // A malformed address yields no allowed host.
        }
        return null;
    }

    /** Servers may be separated by commas, semicolons, spaces or newlines. */
    static List<String> splitServers(String value) {
        final List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split("[,;\\s]+")) {
            final String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }
}
