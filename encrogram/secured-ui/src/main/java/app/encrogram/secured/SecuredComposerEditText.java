package app.encrogram.secured;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.text.style.ReplacementSpan;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.EditTextCaption;

/**
 * The Secured message box. Telegram's EditTextCaption with everything that could reach Telegram's network, file or
 * image loaders, or leak Secured text to other apps, switched off:
 * <ul>
 * <li>paste is plain text only (no HTML to custom-emoji conversion);</li>
 * <li>copy and cut are plain text and marked sensitive;</li>
 * <li>selection menus keep only cut, copy, paste, select all, undo, redo (no Share, translate, format, link,
 * process-text);</li>
 * <li>an input filter strips image, animated emoji, link and replacement spans from every input path, including
 * Emoji.EmojiSpan: its bitmaps come from EmojiPack (which can fetch emoji packs through Telegram's loaders), so the
 * system font renders emoji instead;</li>
 * <li>the keyboard is told not to learn (IME_FLAG_NO_PERSONALIZED_LEARNING).</li>
 * </ul>
 */
public class SecuredComposerEditText extends EditTextCaption {

    public SecuredComposerEditText(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        drawAnimatedEmojiDrawables = false;
        setFilters(new InputFilter[]{new SpanStripFilter()});
    }

    // ---- input ------------------------------------------------------------------------------------------------

    private static boolean isRemote(Object span) {
        return span instanceof AnimatedEmojiSpan || span instanceof ImageSpan || span instanceof DynamicDrawableSpan
                || span instanceof ReplacementSpan || span instanceof ClickableSpan;
    }

    private static final class SpanStripFilter implements InputFilter {
        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            if (!(source instanceof Spanned)) return null;
            final Spanned spanned = (Spanned) source;
            final Object[] all = spanned.getSpans(start, end, Object.class);
            boolean found = false;
            for (Object o : all) {
                if (isRemote(o)) {
                    found = true;
                    break;
                }
            }
            if (!found) return null;
            final SpannableStringBuilder copy = new SpannableStringBuilder(source, start, end);
            for (Object o : copy.getSpans(0, copy.length(), Object.class)) {
                if (isRemote(o)) copy.removeSpan(o);
            }
            return copy;
        }
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        final InputConnection ic = super.onCreateInputConnection(outAttrs);
        outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        return ic;
    }

    // ---- clipboard ----------------------------------------------------------------------------------------------

    private void insertPlain(CharSequence text) {
        if (text == null || text.length() == 0) return;
        final Editable editable = getText();
        if (editable == null) return;
        final int a = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd()));
        final int b = Math.min(editable.length(), Math.max(getSelectionStart(), getSelectionEnd()));
        editable.replace(a, b, text.toString());
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            try {
                final ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                final ClipData clip = cm == null ? null : cm.getPrimaryClip();
                if (clip != null) {
                    final StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        final CharSequence t = clip.getItemAt(i).coerceToText(getContext());
                        if (t != null) sb.append(t.toString());
                    }
                    insertPlain(sb.toString());
                }
            } catch (Exception ignore) {
                // Fail closed: nothing is pasted.
            }
            return true;
        }
        if (id == android.R.id.copy || id == android.R.id.cut) {
            final Editable editable = getText();
            final int a = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd()));
            final int b = editable == null ? 0 : Math.min(editable.length(), Math.max(getSelectionStart(), getSelectionEnd()));
            if (editable != null && b > a) {
                SecuredSheets.copyToClipboard(getContext(), editable.subSequence(a, b).toString(), true);
                if (id == android.R.id.cut) {
                    editable.delete(a, b);
                } else {
                    setSelection(a, b);
                }
            }
            return true;
        }
        if (id == android.R.id.shareText) {
            return true;
        }
        return super.onTextContextMenuItem(id);
    }

    // ---- selection and insertion menus --------------------------------------------------------------------------

    private static boolean allowed(int id) {
        return id == android.R.id.cut || id == android.R.id.copy || id == android.R.id.paste
                || id == android.R.id.pasteAsPlainText || id == android.R.id.selectAll
                || id == android.R.id.undo || id == android.R.id.redo;
    }

    private static void prune(Menu menu) {
        for (int i = menu.size() - 1; i >= 0; i--) {
            final MenuItem item = menu.getItem(i);
            if (!allowed(item.getItemId())) menu.removeItem(item.getItemId());
        }
    }

    private static ActionMode.Callback restrict(final ActionMode.Callback inner) {
        if (inner == null) return null;
        if (Build.VERSION.SDK_INT >= 23) {
            return new ActionMode.Callback2() {
                @Override
                public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                    final boolean r = inner.onCreateActionMode(mode, menu);
                    prune(menu);
                    return r;
                }

                @Override
                public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                    final boolean r = inner.onPrepareActionMode(mode, menu);
                    prune(menu);
                    return r;
                }

                @Override
                public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                    return allowed(item.getItemId()) && inner.onActionItemClicked(mode, item);
                }

                @Override
                public void onDestroyActionMode(ActionMode mode) {
                    inner.onDestroyActionMode(mode);
                }

                @Override
                public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                    if (inner instanceof ActionMode.Callback2) {
                        ((ActionMode.Callback2) inner).onGetContentRect(mode, view, outRect);
                    } else {
                        super.onGetContentRect(mode, view, outRect);
                    }
                }
            };
        }
        return new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                final boolean r = inner.onCreateActionMode(mode, menu);
                prune(menu);
                return r;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                final boolean r = inner.onPrepareActionMode(mode, menu);
                prune(menu);
                return r;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                return allowed(item.getItemId()) && inner.onActionItemClicked(mode, item);
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                inner.onDestroyActionMode(mode);
            }
        };
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback, int type) {
        return super.startActionMode(restrict(callback), type);
    }

    @Override
    public ActionMode startActionMode(ActionMode.Callback callback) {
        return super.startActionMode(restrict(callback));
    }
}
