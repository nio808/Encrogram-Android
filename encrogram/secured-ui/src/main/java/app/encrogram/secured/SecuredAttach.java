package app.encrogram.secured;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

import app.encrogram.core.api.MediaInput;
import app.encrogram.core.api.PreparedMedia;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredMessage;
import app.encrogram.core.api.SecuredResult;

/**
 * The attach button of a Secured chat: a two-item menu (photo or video, file), the system picker, then the engine's
 * prepareMedia (it reads the picked file, strips metadata and stores it encrypted) and the send sheet.
 * No camera, no Telegram gallery: those load through Telegram's image and file paths. This class never opens the Uri.
 * Every prepared file ends in exactly one of: sent (the engine owns it) or discarded.
 */
final class SecuredAttach {

    static final int REQUEST_GALLERY = 0x5EC1, REQUEST_FILE = 0x5EC2;

    interface Host {
        BaseFragment fragment();
        SecuredEngine engine();
        long chatId();
        Theme.ResourcesProvider resources();
        MediaBitmaps bitmaps();
        /** Becomes the caption draft. */
        String composerText();
        /** The message being replied to, or null. */
        Long replyToId();
        /** Clear the composer (when usedComposerText) and the reply strip. */
        void onMediaSent(SecuredMessage m, boolean usedComposerText);
        void onMediaFailed(SecuredError e);
    }

    private final Host host;
    private int pendingRequest;          // 0 = none: only a result for a request we started is handled
    private BottomSheet menu;
    private AlertDialog spinner;
    private SecuredSendMediaSheet sendSheet;
    private boolean preparing;
    private boolean cancelled;           // the user backed out of the spinner while prepareMedia was running
    private boolean destroyed;

    SecuredAttach(Host host) {
        this.host = host;
    }

    void showMenu() {
        final Activity activity = host.fragment().getParentActivity();
        if (activity == null || destroyed || preparing) return;
        dismissMenu();
        final Context context = activity;
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, false, host.resources());
        builder.setItems(
                new CharSequence[]{context.getString(R.string.secmedia_attach_gallery), context.getString(R.string.secmedia_attach_file)},
                new int[]{org.telegram.messenger.R.drawable.msg_photos, org.telegram.messenger.R.drawable.msg_filehq},
                (dialog, which) -> start(which == 0 ? REQUEST_GALLERY : REQUEST_FILE));
        final BottomSheet sheet = builder.create();
        menu = sheet;
        sheet.setOnDismissListener((DialogInterface.OnDismissListener) d -> {
            if (menu == sheet) menu = null;
        });
        Kit.secure(sheet);
        sheet.show();
    }

    private void dismissMenu() {
        final BottomSheet m = menu;
        menu = null;
        if (m != null) m.dismiss();
    }

    private void start(int code) {
        final Intent intent;
        if (code == REQUEST_GALLERY) {
            if (hasPhotoPicker()) {
                intent = new Intent(MediaStore.ACTION_PICK_IMAGES);   // no type: images and videos, one item
            } else {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                intent.addCategory(Intent.CATEGORY_OPENABLE);
            }
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
        }
        try {
            pendingRequest = code;
            host.fragment().startActivityForResult(intent, code);
        } catch (RuntimeException e) {   // ActivityNotFoundException, SecurityException
            pendingRequest = 0;
            showNoPicker();
        }
    }

    private static boolean hasPhotoPicker() {
        if (Build.VERSION.SDK_INT >= 33) return true;
        try {
            return Build.VERSION.SDK_INT >= 30
                    && android.os.ext.SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void showNoPicker() {
        final Activity activity = host.fragment().getParentActivity();
        if (activity == null) return;
        BulletinFactory.of(host.fragment())
                .createErrorBulletin(activity.getString(R.string.secmedia_no_picker), host.resources()).show();
    }

    /** True when the result was ours (also when cancelled), so the caller stops looking. */
    boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_GALLERY && requestCode != REQUEST_FILE) return false;
        if (requestCode != pendingRequest) return false;   // not started by this chat screen
        pendingRequest = 0;
        if (destroyed || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        prepare(data.getData().toString(), requestCode == REQUEST_FILE);
        return true;
    }

    private void prepare(String uri, boolean asFile) {
        final Activity activity = host.fragment().getParentActivity();
        if (activity == null) return;
        preparing = true;
        cancelled = false;
        final AlertDialog d = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, host.resources());
        spinner = d;
        d.setCanceledOnTouchOutside(false);
        d.setCancelable(true);   // Back: the result is discarded when it arrives
        d.setOnCancelListener(x -> cancelled = true);
        Kit.secure(d);
        d.show();
        host.engine().prepareMedia(new MediaInput.Picked(uri, asFile), result -> {
            preparing = false;
            final AlertDialog s = spinner;
            spinner = null;
            if (s != null && s.isShowing()) s.dismiss();
            if (result instanceof SecuredResult.Ok) {
                final app.encrogram.core.api.PreparedMedia prepared = ((SecuredResult.Ok<PreparedMedia>) result).getValue();
                if (destroyed || cancelled || host.fragment().getParentActivity() == null) {
                    host.engine().discardMedia(prepared);
                    return;
                }
                final SecuredSendMediaSheet sheet = new SecuredSendMediaSheet(host, prepared);
                sendSheet = sheet;
                sheet.show(() -> {
                    if (sendSheet == sheet) sendSheet = null;
                });
            } else if (!destroyed && !cancelled) {
                host.onMediaFailed(((SecuredResult.Err) result).getError());
            }
        });
    }

    /** Idempotent. A running prepareMedia is discarded when it returns. */
    void destroy() {
        destroyed = true;
        pendingRequest = 0;
        dismissMenu();
        final AlertDialog s = spinner;
        spinner = null;
        if (s != null && s.isShowing()) s.dismiss();
        final SecuredSendMediaSheet sheet = sendSheet;
        sendSheet = null;
        if (sheet != null) sheet.release();   // discards, or leaves a send in flight to the engine
    }
}
