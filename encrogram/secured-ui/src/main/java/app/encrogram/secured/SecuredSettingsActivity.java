package app.encrogram.secured;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import app.encrogram.core.api.MediaPreview;
import app.encrogram.core.api.MediaStorage;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredProfile;
import app.encrogram.core.api.SecuredResult;

/**
 * Secured settings (from the list's menu): the display name (tap to edit) and the recovery phrase screen.
 * Reuse: the backup screen's frame (SecuredBackupScreen) and Telegram's HeaderCell, TextSettingsCell and
 * TextInfoPrivacyCell on the Secured ResourcesProvider. Only engine calls; nothing from Telegram's network.
 */
final class SecuredSettingsActivity extends SecuredBackupScreen {

    private static final int REQUEST_PHOTO = 0x5EC3;
    private static final int[] KEEP_DAYS = {3, 7, 30, 0};   // 0 = forever

    private String error;
    private boolean photoBusy;
    private MediaPreview shownPreview;
    private Bitmap shownBitmap;
    private String storageText;   // null while loading

    @Override
    int titleRes() {
        return R.string.sec_set_title;
    }

    @Override
    void onBuild(Context context) {
        content.setPadding(0, 0, 0, 0);
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private String currentName() {
        try {
            final SecuredProfile p = engine != null ? engine.profile() : null;
            return p != null ? p.getDisplayName() : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private void refresh() {
        if (fragmentView == null || content == null) return;
        final Context c = fragmentView.getContext();
        final Theme.ResourcesProvider rp = SecuredResourcesProvider.get();
        content.removeAllViews();

        content.addView(photoBlock(c, rp));

        final HeaderCell profileHeader = new HeaderCell(c, rp);
        profileHeader.setText(c.getString(R.string.sec_set_profile));
        content.addView(profileHeader);

        final TextSettingsCell name = new TextSettingsCell(c, rp);
        name.setTextAndValue(c.getString(R.string.sec_set_name), currentName(), false);
        name.setOnClickListener(v -> editName(c));
        content.addView(name);

        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(c, rp);
        info.setText(error != null ? error : c.getString(R.string.sec_set_name_info));
        content.addView(info);

        final TextSettingsCell backup = new TextSettingsCell(c, rp);
        backup.setText(c.getString(R.string.sec_bk_menu), false);
        backup.setOnClickListener(v -> presentFragment(new SecuredBackupSettingsActivity()));
        content.addView(backup);

        addStorage(c, rp);
    }

    // ---- profile photo ---------------------------------------------------------------------------------------

    private MediaPreview currentAvatar() {
        try {
            final SecuredProfile p = engine != null ? engine.profile() : null;
            return p != null ? p.getAvatar() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private View photoBlock(Context c, Theme.ResourcesProvider rp) {
        final MediaPreview av = currentAvatar();
        if (av == null) {
            shownPreview = null;
            shownBitmap = null;
        } else if (!av.equals(shownPreview)) {
            shownPreview = av;
            shownBitmap = MediaBitmaps.decodePreview(av, AvatarImage.SIZE, AvatarImage.SIZE);   // bounded, uncached
        }
        final LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Kit.color(Theme.key_windowBackgroundWhite));
        final AvatarDrawable fallback = new AvatarDrawable(rp);
        Kit.setAvatar(fallback, 0, currentName());
        final AvatarImage.CircleView photo = new AvatarImage.CircleView(c, fallback);
        photo.setPhoto(shownBitmap);
        box.addView(photo, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 20, 0, 12));

        final TextSettingsCell set = new TextSettingsCell(c, rp);
        set.setText(c.getString(R.string.sec_r2s_set_photo), shownBitmap != null);
        set.setOnClickListener(v -> pickPhoto(c));
        box.addView(set);
        if (shownBitmap != null) {
            final TextSettingsCell remove = new TextSettingsCell(c, rp);
            remove.setText(c.getString(R.string.sec_r2s_remove_photo), false);
            remove.setTextColor(Kit.color(Theme.key_text_RedBold));
            remove.setOnClickListener(v -> storePhoto(c, null));
            box.addView(remove);
        }
        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(c, rp);
        info.setText(c.getString(R.string.sec_r2s_photo_info));
        box.addView(info);
        return box;
    }

    private void pickPhoto(Context c) {
        if (engine == null || photoBusy) return;
        try {
            startActivityForResult(AvatarImage.pickIntent(), REQUEST_PHOTO);
        } catch (RuntimeException e) {
            toast(c.getString(R.string.sec_r2s_no_picker));
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQUEST_PHOTO || destroyed || fragmentView == null || photoBusy) return;
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
        final Context c = fragmentView.getContext();
        photoBusy = true;
        AvatarImage.prepare(c, data.getData(), jpeg -> {
            if (destroyed) {
                photoBusy = false;
                return;
            }
            if (jpeg == null) {
                photoBusy = false;
                toast(c.getString(R.string.sec_r2s_photo_failed));
            } else {
                storePhoto(c, jpeg);
            }
        });
    }

    private void storePhoto(Context c, byte[] jpeg) {
        if (engine == null) return;
        photoBusy = true;
        try {
            engine.setProfilePhoto(jpeg, result -> ui(() -> {
                photoBusy = false;
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) toast(errorText(c, ((SecuredResult.Err) result).getError()));
                refresh();
            }));
        } catch (Throwable t) {
            photoBusy = false;
            toast(c.getString(R.string.sec_bk_err_unknown));
        }
    }

    private void toast(String m) {
        if (destroyed || getParentActivity() == null) return;
        BulletinFactory.of(this).createErrorBulletin(m, SecuredResourcesProvider.get()).show();
    }

    // ---- data and storage ------------------------------------------------------------------------------------

    private void addStorage(Context c, Theme.ResourcesProvider rp) {
        final HeaderCell h = new HeaderCell(c, rp);
        h.setText(c.getString(R.string.sec_r2s_storage_header));
        content.addView(h);

        final TextSettingsCell keep = new TextSettingsCell(c, rp);
        keep.setTextAndValue(c.getString(R.string.sec_r2s_keep_media), keepLabel(c, retention()), true);
        keep.setOnClickListener(v -> chooseRetention(c));
        content.addView(keep);

        final TextSettingsCell size = new TextSettingsCell(c, rp);
        size.setTextAndValue(c.getString(R.string.sec_r2s_on_phone), storageText != null ? storageText : "", true);
        content.addView(size);

        final TextSettingsCell clear = new TextSettingsCell(c, rp);
        clear.setText(c.getString(R.string.sec_r2s_clear), false);
        clear.setTextColor(Kit.color(Theme.key_text_RedBold));
        clear.setOnClickListener(v -> confirmClear(c));
        content.addView(clear);

        final TextInfoPrivacyCell info = new TextInfoPrivacyCell(c, rp);
        info.setText(c.getString(R.string.sec_r2s_keep_info));
        content.addView(info);
        loadStorage(c);
    }

    private Integer retention() {
        try {
            return engine != null ? engine.mediaRetentionDays() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String keepLabel(Context c, Integer days) {
        if (days == null || days <= 0) return c.getString(R.string.sec_r2s_keep_forever);
        if (days <= 3) return c.getString(R.string.sec_r2s_keep_3d);
        if (days <= 7) return c.getString(R.string.sec_r2s_keep_1w);
        return c.getString(R.string.sec_r2s_keep_1m);
    }

    private void loadStorage(Context c) {
        if (engine == null) return;
        try {
            engine.mediaStorage(result -> ui(() -> {
                if (destroyed) return;
                final String next = result instanceof SecuredResult.Ok
                        ? AndroidUtilities.formatFileSize(((MediaStorage) ((SecuredResult.Ok<?>) result).getValue()).getBytes())
                        : c.getString(R.string.sec_r2s_unknown);
                if (next.equals(storageText)) return;
                storageText = next;
                refreshKeepingStorage();
            }));
        } catch (Throwable t) {
            storageText = c.getString(R.string.sec_r2s_unknown);
        }
    }

    /** Redraws with the loaded size without asking the engine again (refresh() would loop). */
    private void refreshKeepingStorage() {
        final String keep = storageText;
        refresh();
        storageText = keep;
    }

    private void chooseRetention(Context c) {
        if (engine == null) return;
        final CharSequence[] labels = {c.getString(R.string.sec_r2s_keep_3d), c.getString(R.string.sec_r2s_keep_1w),
                c.getString(R.string.sec_r2s_keep_1m), c.getString(R.string.sec_r2s_keep_forever)};
        final AlertDialog.Builder b = new AlertDialog.Builder(c, SecuredResourcesProvider.get());
        b.setTitle(c.getString(R.string.sec_r2s_keep_media));
        b.setItems(labels, (d, which) -> {
            final Integer days = KEEP_DAYS[which] == 0 ? null : KEEP_DAYS[which];
            try {
                engine.setMediaRetention(days, result -> ui(() -> {
                    if (destroyed) return;
                    if (result instanceof SecuredResult.Err) toast(errorText(c, ((SecuredResult.Err) result).getError()));
                    refresh();
                }));
            } catch (Throwable t) {
                toast(c.getString(R.string.sec_bk_err_unknown));
            }
        });
        b.setNegativeButton(c.getString(R.string.sec_bs_cancel), null);
        Kit.show(this, b.create());
    }

    private void confirmClear(Context c) {
        if (engine == null) return;
        final AlertDialog.Builder b = new AlertDialog.Builder(c, SecuredResourcesProvider.get());
        b.setTitle(c.getString(R.string.sec_r2s_clear_title));
        b.setMessage(c.getString(R.string.sec_r2s_clear_text));
        b.setNegativeButton(c.getString(R.string.sec_bs_cancel), null);
        b.setPositiveButton(c.getString(R.string.sec_r2s_clear_confirm), (d, w) -> {
            try {
                engine.clearMedia(result -> ui(() -> {
                    if (destroyed) return;
                    if (result instanceof SecuredResult.Err) {
                        toast(errorText(c, ((SecuredResult.Err) result).getError()));
                    } else {
                        storageText = null;
                    }
                    refresh();
                }));
            } catch (Throwable t) {
                toast(c.getString(R.string.sec_bk_err_unknown));
            }
        });
        final AlertDialog dialog = b.create();
        Kit.show(this, dialog);
        dialog.redPositive();
    }

    private void editName(Context c) {
        if (engine == null) return;
        final EditText input = new EditText(c);
        input.setText(currentName());
        input.setSelection(input.getText().length());
        input.setHint(R.string.sec_profile_name_hint);
        input.setHintTextColor(Kit.color(Theme.key_windowBackgroundWhiteGrayText));
        input.setTextColor(Kit.color(Theme.key_windowBackgroundWhiteBlackText));
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);   // keyboard doesn't learn Secured names
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setBackground(Kit.roundRect(Theme.multAlpha(Kit.color(Theme.key_windowBackgroundWhiteBlackText), 0.06f), 12));
        final FrameLayout box = new FrameLayout(c);
        box.addView(input, new FrameLayout.LayoutParams(-1, -2));
        box.setPadding(dp(20), dp(4), dp(20), 0);

        final AlertDialog.Builder b = new AlertDialog.Builder(c, SecuredResourcesProvider.get());
        b.setTitle(c.getString(R.string.sec_set_name));
        b.setView(box);
        b.setNegativeButton(c.getString(R.string.sec_bs_cancel), null);
        b.setPositiveButton(c.getString(R.string.sec_set_save), (d, w) -> {
            AndroidUtilities.hideKeyboard(input);
            save(c, input.getText().toString().trim());
        });
        Kit.show(this, b.create());
        input.requestFocus();
        AndroidUtilities.showKeyboard(input);
    }

    private void save(Context c, String name) {
        if (engine == null || name.isEmpty() || name.equals(currentName())) return;
        error = null;
        try {
            engine.updateProfile(name, result -> ui(() -> {
                if (destroyed) return;
                if (result instanceof SecuredResult.Err) {
                    final SecuredError e = ((SecuredResult.Err) result).getError();
                    final String m = e.getMessage();
                    error = m == null || m.isEmpty() ? c.getString(R.string.sec_bk_err_unknown) : m;
                }
                refresh();
            }));
        } catch (Throwable t) {
            error = c.getString(R.string.sec_bk_err_unknown);
            refresh();
        }
    }
}
