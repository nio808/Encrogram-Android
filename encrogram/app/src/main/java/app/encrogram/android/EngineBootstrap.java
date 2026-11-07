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
 * (written from encrogram/local.properties) and derives the allowed relay hosts from them.
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
        return SecuredEngines.create(context.getApplicationContext(), new RelayConfig(smp, xftp, new ArrayList<>(hosts)));
    }

    /** Servers may be separated by commas, semicolons, spaces or newlines. */
    private static List<String> splitServers(String value) {
        final List<String> out = new ArrayList<>();
        if (value == null) return out;
        for (String part : value.split("[,;\\s]+")) {
            final String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /** smp://fingerprint[:password]@host[:port] and xftp://... : the host after the last '@'. */
    private static void addHost(Set<String> hosts, String server) {
        String rest = server;
        final int scheme = rest.indexOf("://");
        if (scheme >= 0) rest = rest.substring(scheme + 3);
        final int at = rest.lastIndexOf('@');
        if (at >= 0) rest = rest.substring(at + 1);
        // Several hosts for one server may be comma separated inside the address; split_servers already handled that.
        try {
            final String host = new URI("smp://" + rest).getHost();
            if (host != null && !host.isEmpty()) hosts.add(host.toLowerCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
            // A malformed address yields no allowed host, so the engine fails closed.
        }
    }
}
