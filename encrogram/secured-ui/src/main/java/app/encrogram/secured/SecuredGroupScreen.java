package app.encrogram.secured;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.LinearLayoutManager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredError;
import app.encrogram.core.api.SecuredListener;
import app.encrogram.core.api.SecuredMessage;
import app.encrogram.core.api.SecuredResult;

/**
 * Shared frame of the group screens (new group, group info): Telegram's ActionBar plus a RecyclerListView, FLAG_SECURE
 * while visible, and one engine listener that asks the subclass to refresh (coalesced, on the UI thread).
 * Same shape as {@link SecuredBackupScreen}. Reads no Telegram data; every engine call goes through the engine only.
 */
abstract class SecuredGroupScreen extends BaseFragment implements SecuredScreen {

    protected SecuredEngine engine;
    protected boolean destroyed;
    protected RecyclerListView listView;
    private FlagSecureReason flagSecure;
    private boolean listening;

    abstract CharSequence title(Context context);

    abstract RecyclerListView.SelectionAdapter buildAdapter(Context context);

    /** Called on the UI thread after the engine reported a chat-list or message change (coalesced). */
    void onEngineChanged() {}

    /** Chat whose messages (service rows such as "X joined") also trigger {@link #onEngineChanged}; none by default. */
    long watchedChatId() {
        return Long.MIN_VALUE;
    }

    /** Extra views under the list (a bottom bar), or null. */
    View buildFooter(Context context) {
        return null;
    }

    private final Runnable refreshRun = () -> {
        if (!destroyed) onEngineChanged();
    };

    private void scheduleRefresh() {
        AndroidUtilities.cancelRunOnUIThread(refreshRun);
        AndroidUtilities.runOnUIThread(refreshRun, 150);
    }

    private final SecuredListener listener = new SecuredListener() {
        @Override
        public void onChatsChanged() {
            scheduleRefresh();
        }

        @Override
        public void onMessageReceived(SecuredMessage message) {
            if (message.getChatId() == watchedChatId()) scheduleRefresh();
        }

        @Override
        public void onMessageChanged(SecuredMessage message) {
            if (message.getChatId() == watchedChatId()) scheduleRefresh();
        }
    };

    @Override
    public Theme.ResourcesProvider getResourceProvider() {
        return SecuredResourcesProvider.get();
    }

    @Override
    public boolean onFragmentCreate() {
        try {
            engine = SecuredUi.engine();
        } catch (Throwable t) {
            engine = null;
        }
        if (engine == null) return false;
        try {
            engine.addListener(listener);
            listening = true;
        } catch (Throwable t) {
            listening = false;
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        hasOwnBackground = true;
        actionBar.setAddToContainer(false);
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(title(context));
        actionBar.setBackgroundColor(getThemedColor(Theme.key_actionBarDefault));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
                else onMenuItem(id);
            }
        });

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        column.addView(actionBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        listView = new RecyclerListView(context, SecuredResourcesProvider.get());
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(buildAdapter(context));
        // Names and links are never offered to autofill services.
        listView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        column.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
        final View footer = buildFooter(context);
        if (footer != null) column.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        fragmentView = column;
        return column;
    }

    void onMenuItem(int id) {}

    @Override
    public void onResume() {
        super.onResume();
        SecuredUi.nudgeLink();   // a link that waited (passcode, transition) shows now that this screen is resumed
        if (flagSecure == null && getParentActivity() != null) {
            flagSecure = new FlagSecureReason(getParentActivity().getWindow(), () -> true);
        }
        if (flagSecure != null) flagSecure.attach();
    }

    // Kept through onPause (Home, Recents snapshot); dropped only when covered or gone, as in SecuredChatActivity.
    @Override
    public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        if (flagSecure != null) flagSecure.detach();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        AndroidUtilities.cancelRunOnUIThread(refreshRun);
        if (flagSecure != null) flagSecure.detach();
        if (engine != null && listening) {
            listening = false;
            try {
                engine.removeListener(listener);
            } catch (Throwable ignored) {
            }
        }
        super.onFragmentDestroy();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    protected static void ui(Runnable r) {
        AndroidUtilities.runOnUIThread(r);
    }

    protected static String errorText(SecuredResult.Err err) {
        final SecuredError e = err.getError();
        return e.getMessage();
    }

    /** A short red bulletin. [message] never holds names or message text. */
    protected void showError(String message) {
        if (destroyed || getParentActivity() == null) return;
        BulletinFactory.of(this).createErrorBulletin(message, SecuredResourcesProvider.get()).show();
    }

    /** The engine call threw: say so with the exception class only. */
    protected void showThrown(Throwable t, int formatRes) {
        final Context c = getParentActivity();
        if (c == null) return;
        showError(c.getString(formatRes, t.getClass().getSimpleName()));
    }
}
