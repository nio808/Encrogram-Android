package app.encrogram.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.telegram.ui.LaunchActivity;

import app.encrogram.secured.SecuredUi;

/**
 * Receives https://encrogram.nytmanager.com/i#..., /a#... and /g#... (group) links.
 *
 * It only checks the link's shape (https, our host, exact path, length) and contacts nothing; anything else is dropped. It hands the link, in memory only, to the Secured connect flow
 * (which runs the engine's checkLink before connecting) and brings the normal app forward. The Secured page
 * then opens the connect sheet with the link prefilled. Telegram's LaunchActivity is not involved in
 * deciding what the link means.
 */
public class SecuredLinkActivity extends Activity {

    private static final int MAX_LINK_LENGTH = 2048;

    /** Exported activity: explicit intents skip the manifest filter, so check everything here. */
    private static boolean isValidLink(Uri u) {
        if (u == null || !u.isHierarchical()) return false;
        final String host = u.getHost();
        final String path = u.getPath();
        return "https".equalsIgnoreCase(u.getScheme())
                && host != null && host.equalsIgnoreCase(BuildConfig.SECURED_LINK_HOST)
                && u.getUserInfo() == null
                && (u.getPort() == -1 || u.getPort() == 443)
                && ("/i".equals(path) || "/a".equals(path) || "/g".equals(path))
                && u.toString().length() <= MAX_LINK_LENGTH;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Uri data = getIntent() != null ? getIntent().getData() : null;
        if (!isValidLink(data)) {
            finish();
            return;
        }
        SecuredUi.deliverLink(data.toString());
        final Intent open = new Intent(this, LaunchActivity.class);
        open.setAction(Intent.ACTION_MAIN);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(open);
        finish();
    }
}
