package app.encrogram.secured;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.EncrogramHooks;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.IntroActivity;
import org.telegram.ui.LoginActivity;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import app.encrogram.core.background.SecuredBackground;

/**
 * The real implementation behind EncrogramHooks. Each method is the target of a one-line call in Telegram's code
 * and does nothing unless the fragment is the Chats page that this registry wrapped.
 */
final class SecuredHooks implements EncrogramHooks.Hooks {

    // Weak values: a host strongly references its own key (the DialogsActivity), so strong values would never be freed.
    private final Map<BaseFragment, WeakReference<EncrogramChatsHost>> hosts = new WeakHashMap<>();

    private EncrogramChatsHost hostFor(BaseFragment fragment) {
        final WeakReference<EncrogramChatsHost> ref = hosts.get(fragment);
        return ref != null ? ref.get() : null;
    }

    /** Called by the host when it is destroyed. */
    void release(BaseFragment key) {
        try {
            hosts.remove(key);
        } catch (Throwable ignored) {
        }
    }

    /** Only the exception class name is kept; never its text. */
    private static void fail(String hook, Throwable t) {
        try {
            Log.w("EncrogramHooks", hook + " failed: " + t.getClass().getSimpleName());
        } catch (Throwable ignored) {
        }
    }

    @Override
    public BaseFragment wrapChats(DialogsActivity chats) {
        try {
            final EncrogramChatsHost host = new EncrogramChatsHost(chats);
            host.setOnDestroy(() -> release(chats));
            hosts.put(chats, new WeakReference<>(host));
            return host;
        } catch (Throwable t) {
            fail("wrapChats", t);
            hosts.remove(chats);
            return chats;
        }
    }

    @Override
    public CharSequence chatsTitle(DialogsActivity fragment, ActionBar actionBar, CharSequence title) {
        ViewGroup container = null;
        try {
            final EncrogramChatsHost host = hostFor(fragment);
            container = actionBar.getTitlesContainer();
            if (host == null || container == null) {
                return title;
            }
            // createView can run again on the same bar: keep exactly one control.
            removeSwitchers(container);
            final Context context = actionBar.getContext();
            final ChatsSwitcherView view = new ChatsSwitcherView(context, host);
            view.anchorTo(actionBar);
            container.addView(view, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            // The title itself becomes a blank of the same width, so Telegram's emoji status still sits after it.
            final SpannableStringBuilder blank = new SpannableStringBuilder(" ");
            blank.setSpan(new Spacer(ChatsSwitcherView.widthFor(context)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return blank;
        } catch (Throwable t) {
            fail("chatsTitle", t);
            try {
                if (container != null) {
                    removeSwitchers(container);
                }
            } catch (Throwable ignored) {
            }
            return title;
        }
    }

    private static void removeSwitchers(ViewGroup container) {
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            if (container.getChildAt(i) instanceof ChatsSwitcherView) {
                container.removeViewAt(i);
            }
        }
    }

    @Override
    public String routeTitleOverlay(BaseFragment fragment, String title, int titleId) {
        try {
            final EncrogramChatsHost host = hostFor(fragment);
            if (host == null) {
                return title;
            }
            // Telegram's connection state goes to the Telegram segment only; it never covers the control and
            // never says anything about Secured.
            host.setTelegramStatus(title);
            return null;
        } catch (Throwable t) {
            fail("routeTitleOverlay", t);
            return title;
        }
    }

    @Override
    public ImageView storiesTitleView(Context context, BaseFragment fragment, ImageView logo) {
        try {
            final EncrogramChatsHost host = hostFor(fragment);
            if (host == null) {
                return logo;
            }
            final ChatsSwitcherView view = new ChatsSwitcherView(context, host);
            view.shiftForStoriesLogo();
            return view;
        } catch (Throwable t) {
            fail("storiesTitleView", t);
            return logo;
        }
    }

    @Override
    public float storiesTitleVisibility(View logo, float titleVisibility) {
        try {
            return logo instanceof ChatsSwitcherView ? 0f : titleVisibility;
        } catch (Throwable t) {
            return titleVisibility;
        }
    }

    /**
     * A tap on a Secured notification. Only our own action is touched; everything else is Telegram's.
     * Passcode: if Telegram's passcode is showing or due, return false untouched, so Telegram's own flow runs and
     * re-delivers this intent (needShowPasscode(false): the reset variant would clear the state Telegram checks next).
     * No Telegram account: the id stays pending (in memory); with Secured-only on, the standalone list consumes it,
     * otherwise Telegram carries on.
     */
    @Override
    public boolean handleIntent(Activity activity, Intent intent) {
        try {
            if (intent == null || !SecuredUi.ACTION_OPEN_CHAT.equals(intent.getAction())) {
                return false;
            }
            // LaunchActivity is exported: only intents from our own notifications (per-install token) open a chat.
            if (!SecuredBackground.isOwnTapIntent(activity, intent)) {
                return false;
            }
            // Reopened from Recents: Android re-delivers the old notification intent; don't reopen that chat again.
            if ((intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
                return false;
            }
            if (AndroidUtilities.needShowPasscode(false) || SharedConfig.isWaitingForPasscodeEnter) {
                return false;
            }
            SecuredUi.deliverOpenChat(intent.getLongExtra(SecuredUi.EXTRA_CHAT_ID, SecuredUi.NO_CHAT));
            // Logged out, only a live standalone Secured list consumes it (mid-login, Telegram's LoginActivity is the start screen).
            final boolean handled = UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()
                    || (SecuredUi.securedOnly() && SecuredUi.hasOpenChatListener());
            if (handled) {
                intent.setAction(null); // as Telegram does: an activity restored after process death must not reopen the chat
            }
            return handled;
        } catch (Throwable t) {
            fail("handleIntent", t);
            return false;
        }
    }

    /** Telegram's start screen when logged out: the standalone list if Secured-only was chosen, else the welcome. */
    @Override
    public BaseFragment loggedOutIntro(BaseFragment intro) {
        try {
            return SecuredUi.securedOnly() ? new SecuredDialogsActivity() : new SecuredWelcomeActivity();
        } catch (Throwable t) {
            fail("loggedOutIntro", t);
            return intro;
        }
    }

    // ---- Telegram asleep: no Telegram account, so no Telegram network and no push token --------------------------
    //
    // Asleep = no Telegram account is activated and no Telegram login is half done, decided once per process before
    // Telegram's network starts. That covers the welcome screen and the Secured-only list. While asleep, Telegram's
    // network layer sees "no network" (its own offline state: no socket, no DNS fallback) and its push-token request
    // waits. Opening Telegram's intro or login screen wakes it with the call Telegram makes when the network returns.
    // With a Telegram account the state is "awake" from the first call, and every hook below returns Telegram's own
    // behaviour unchanged.

    private static final int TG_UNDECIDED = 0;
    private static final int TG_ASLEEP = 1;
    private static final int TG_AWAKE = 2;

    private final Object telegramLock = new Object();
    private volatile int telegramState = TG_UNDECIDED;
    private Runnable heldPush;   // guarded by telegramLock

    @Override
    public boolean keepTelegramOffline() {
        return telegramAsleep();
    }

    @Override
    public boolean holdTelegramPush(Runnable initPushServices) {
        try {
            synchronized (telegramLock) {
                if (!telegramAsleep()) {
                    return false;
                }
                heldPush = initPushServices;
                return true;
            }
        } catch (Throwable t) {
            fail("holdTelegramPush", t);
            return false;
        }
    }

    @Override
    public void willPresentFragment(BaseFragment fragment) {
        try {
            if (telegramState != TG_AWAKE && (fragment instanceof IntroActivity || fragment instanceof LoginActivity)) {
                wakeTelegram();
            }
        } catch (Throwable t) {
            fail("willPresentFragment", t);
        }
    }

    /** Hot path (every isNetworkOnline call): one volatile read once decided. */
    private boolean telegramAsleep() {
        int state = telegramState;
        if (state == TG_UNDECIDED) {
            synchronized (telegramLock) {
                if (telegramState == TG_UNDECIDED) {
                    boolean asleep;
                    try {
                        asleep = !telegramInUse();
                    } catch (Throwable t) {
                        fail("telegramAsleep", t);
                        asleep = false;   // when unsure, behave exactly like Telegram
                    }
                    telegramState = asleep ? TG_ASLEEP : TG_AWAKE;
                    debugLog(asleep ? "asleep" : "awake");
                }
                state = telegramState;
            }
        }
        return state == TG_ASLEEP;
    }

    /** An activated Telegram account, or a half-done login (LaunchActivity then reopens LoginActivity at start). */
    private static boolean telegramInUse() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final UserConfig config = UserConfig.getInstance(a);
            config.loadConfig();   // idempotent; Telegram loads the same file at start
            if (config.isClientActivated()) {
                return true;
            }
        }
        return LoginActivity.loadCurrentState(false, 0).getInt("currentViewNum", 0) != 0;
    }

    private void wakeTelegram() {
        final Runnable push;
        synchronized (telegramLock) {
            final boolean wasAsleep = telegramState == TG_ASLEEP;
            telegramState = TG_AWAKE;
            push = heldPush;
            heldPush = null;
            if (!wasAsleep) {
                return;
            }
        }
        debugLog("woken");
        // Telegram's own reaction to "network is back": each account re-reads isNetworkOnline and reconnects.
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            ConnectionsManager.getInstance(a).checkConnection();
        }
        if (push != null) {
            push.run();   // Telegram's initPushServices, now unheld
        }
    }

    /** Debug builds only: one content-free state line (logcat tag EncrogramHooks) for the device test. */
    private static void debugLog(String state) {
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context != null && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                Log.i("EncrogramHooks", "telegram network " + state);
            }
        } catch (Throwable ignored) {
        }
    }

    /** An invisible span of a fixed width. */
    private static final class Spacer extends ReplacementSpan {
        private final int width;

        Spacer(int width) {
            this.width = width;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            return width;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
        }
    }
}
