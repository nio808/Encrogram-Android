package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import app.encrogram.core.api.MediaKind;
import app.encrogram.core.api.PreparedMedia;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredMessage;
import app.encrogram.core.api.SecuredResult;

/**
 * The send confirmation for one prepared file: preview (or a file row), a one-line note, a caption field and Send.
 * The prepared file is sent once or discarded once: closing the sheet without Send discards it.
 */
final class SecuredSendMediaSheet {

    private static final int MAX_CAPTION = 1024;
    private static final int MAX_PREVIEW_DP = 240;

    private final SecuredAttach.Host host;
    private final PreparedMedia prepared;
    private final String composerAtOpen;
    private BottomSheet sheet;
    private SecuredComposerEditText caption;
    private TextView sendButton;
    private Runnable onClosed;
    private boolean sending;
    private boolean finished;      // sent (the engine owns the file) or discarded: never both
    private boolean hostGone;      // the chat screen is destroyed: report nothing to it

    SecuredSendMediaSheet(SecuredAttach.Host host, PreparedMedia prepared) {
        this.host = host;
        this.prepared = prepared;
        final String t = host.composerText();
        this.composerAtOpen = t == null ? "" : t;
    }

    void show(Runnable onClosed) {
        this.onClosed = onClosed;
        final Context context = host.fragment().getParentActivity();
        if (context == null) {
            discardOnce();
            notifyClosed();
            return;
        }
        final Theme.ResourcesProvider rp = host.resources();
        final int textColor = Kit.color(Theme.key_windowBackgroundWhiteBlackText);
        final int grayColor = Kit.color(Theme.key_windowBackgroundWhiteGrayText);
        final MediaKind kind = prepared.getKind();

        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(12));

        Bitmap bitmap = null;
        if (kind != MediaKind.FILE && kind != MediaKind.VOICE && prepared.getPreview() != null) {
            final int w = Math.max(dp(120), Math.min(AndroidUtilities.displaySize.x, dp(480)) - dp(32));
            bitmap = MediaBitmaps.decodePreview(prepared.getPreview(), w, dp(MAX_PREVIEW_DP));
        }
        if (bitmap != null) {
            final ImageView image = new ImageView(context);
            image.setImageBitmap(bitmap);
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setMaxHeight(dp(MAX_PREVIEW_DP));
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            image.setClipToOutline(true);
            box.addView(image, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
            if (kind == MediaKind.VIDEO && prepared.getDurationSeconds() != null) {
                final TextView d = Kit.text(context, MediaText.duration(prepared.getDurationSeconds()), 13, grayColor, false);
                box.addView(d, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 6, 0, 0));
            }
        } else {
            box.addView(fileRow(context, textColor, grayColor), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        // Never claims location was removed (not tested on every phone yet).
        final int note = kind == MediaKind.IMAGE ? R.string.secmedia_photo_standard : prepared.getUnchanged() ? R.string.secmedia_sent_as_is : 0;
        if (note != 0) {
            box.addView(Kit.text(context, context.getString(note), 13, grayColor, false),
                    LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));
        }

        if (kind != MediaKind.VOICE) {
            caption = new SecuredComposerEditText(context, rp);
            caption.setHint(context.getString(R.string.secmedia_caption_hint));
            caption.setHintTextColor(grayColor);
            caption.setTextColor(textColor);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            caption.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            caption.setMaxLines(4);
            caption.setPadding(dp(12), dp(10), dp(12), dp(10));
            caption.setBackground(Kit.roundRect(Theme.multAlpha(textColor, 0.06f), 10));
            // Keep the composer's span-stripping filter and add the length limit.
            final InputFilter[] old = caption.getFilters();
            final InputFilter[] all = new InputFilter[old.length + 1];
            System.arraycopy(old, 0, all, 0, old.length);
            all[old.length] = new InputFilter.LengthFilter(MAX_CAPTION);
            caption.setFilters(all);
            caption.setText(composerAtOpen.length() > MAX_CAPTION ? composerAtOpen.substring(0, MAX_CAPTION) : composerAtOpen);
            caption.setSelection(caption.length());
            box.addView(caption, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));
        }

        sendButton = Kit.button(context, context.getString(R.string.secmedia_send), true);
        sendButton.setOnClickListener(v -> send());
        box.addView(sendButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 12, 0, 0));

        // needFocus = true: the sheet's window resizes with the keyboard.
        final BottomSheet.Builder builder = new BottomSheet.Builder(context, true, rp);
        builder.setCustomView(box);
        sheet = builder.create();
        sheet.setOnDismissListener((DialogInterface.OnDismissListener) d -> {
            if (!sending) discardOnce();
            notifyClosed();
        });
        Kit.secure(sheet);
        sheet.show();
    }

    private View fileRow(Context context, int textColor, int grayColor) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final ImageView icon = new ImageView(context);
        icon.setImageResource(org.telegram.messenger.R.drawable.msg_filehq);
        icon.setColorFilter(SecuredResourcesProvider.accentFill());
        icon.setBackground(Kit.roundRect(Theme.multAlpha(SecuredResourcesProvider.accentFill(), 0.15f), 12));
        icon.setScaleType(ImageView.ScaleType.CENTER);
        row.addView(icon, LayoutHelper.createLinear(48, 48));
        final LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        final TextView name = Kit.text(context, prepared.getName(), 16, textColor, true);   // user content: display only
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        col.addView(Kit.text(context, MediaText.size(prepared.getSizeBytes()), 14, grayColor, false),
                LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 12, 0, 0, 0));
        return row;
    }

    private void send() {
        if (sending || finished) return;
        sending = true;
        sendButton.setEnabled(false);
        sendButton.setAlpha(0.5f);
        sheet.setCancelable(false);
        sheet.setCanceledOnTouchOutside(false);
        final String text = caption == null ? "" : caption.getText().toString().trim();
        final boolean usedComposer = !composerAtOpen.trim().isEmpty();
        host.engine().sendMedia(host.chatId(), prepared, text, host.replyToId(), result -> {
            sending = false;
            if (result instanceof SecuredResult.Ok) {
                finished = true;   // the engine owns the file now
                dismissSheet();
                if (!hostGone) host.onMediaSent(((SecuredResult.Ok<SecuredMessage>) result).getValue(), usedComposer);
            } else {
                discardOnce();     // a prepared file can't be reused after a failed send
                dismissSheet();
                if (!hostGone) host.onMediaFailed(((SecuredResult.Err) result).getError());
            }
        });
    }

    private void dismissSheet() {
        final BottomSheet s = sheet;
        if (s != null && s.isShowing()) s.dismiss();
    }

    private void discardOnce() {
        if (finished) return;
        finished = true;
        host.engine().discardMedia(prepared);
    }

    private void notifyClosed() {
        final Runnable r = onClosed;
        onClosed = null;
        if (r != null) r.run();
    }

    /** The chat screen is going away: close the sheet. Not sent yet means discarded; a send in flight is left to the engine. */
    void release() {
        hostGone = true;
        if (!sending) discardOnce();
        dismissSheet();
    }
}
