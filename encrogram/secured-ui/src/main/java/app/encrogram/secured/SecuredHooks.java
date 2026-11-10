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
        telegramUsedHere = true;   // Telegram's main tabs exist only with an account: never put it back to sleep in this process
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
    //
    // Back to sleep (HT-2): only after a wake from "asleep" in this process. When a Secured root screen is fully visible
    // again, or the login screen we opened has closed, and no Telegram account is activated, no login is half done and
    // no Telegram intro or login screen is in any stack, the state goes back to "asleep". Then, per account, the network
    // layer is told "no network" (checkConnection: no new socket, no reconnect, no request sent, no DNS-over-HTTPS task
    // started, since onRequestNewServerIpAndPort reads isNetworkOnline too) and gets Telegram's own background pause
    // (native_pauseNetwork), and every open connection is closed at once (dropSockets). "No network" alone would leave an
    // open socket alive: tgnet keeps pinging it. Once Telegram's main tabs have
    // existed in this process (an account was used), it never sleeps again. Anything throwing: awake, as Telegram.

    private static final int TG_UNDECIDED = 0;
    private static final int TG_ASLEEP = 1;
    private static final int TG_AWAKE = 2;

    private final Object telegramLock = new Object();
    private volatile int telegramState = TG_UNDECIDED;
    private Runnable heldPush;   // guarded by telegramLock
    private volatile boolean wokenFromSleep;    // a wake from "asleep" happened here: only then may it sleep again
    private volatile boolean telegramUsedHere;  // Telegram's main tabs were built: an account is in use, stay awake
    private boolean pausedByUs;                 // guarded by telegramLock: tgnet got native_pauseNetwork from us
    private boolean resumeWatch;                // UI thread: the activity-resumed callback below is registered
    // Telegram intro and login screens presented in this process (weak keys: closed ones disappear). UI thread only.
    private final Map<BaseFragment, Boolean> telegramScreens = new WeakHashMap<>();

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
            if (fragment instanceof IntroActivity || fragment instanceof LoginActivity) {
                telegramScreens.put(fragment, Boolean.TRUE);   // HT-2: never sleep while one of these is on screen
                if (telegramState != TG_AWAKE) wakeTelegram();
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
        final boolean unpause;
        synchronized (telegramLock) {
            final boolean wasAsleep = telegramState == TG_ASLEEP;
            telegramState = TG_AWAKE;
            push = heldPush;
            heldPush = null;
            if (!wasAsleep) {
                return;
            }
            wokenFromSleep = true;
            unpause = pausedByUs;
            pausedByUs = false;
        }
        debugLog("woken");
        // Telegram's own reaction to "network is back": each account re-reads isNetworkOnline and reconnects.
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final ConnectionsManager cm = ConnectionsManager.getInstance(a);
            cm.checkConnection();
            if (unpause) {
                // Undo our pause the way Telegram does when the screen comes on: resumed if the app is in the foreground
                // for that account, paused again otherwise (Telegram's own count of resumed screens decides).
                cm.setAppPaused(false, true);
            }
        }
        if (push != null) {
            push.run();   // Telegram's initPushServices, now unheld
        }
    }

    /**
     * HT-2: called when a Secured root screen is fully visible again and when the Telegram login screen we opened has
     * closed. Puts Telegram back to sleep only when it was woken from sleep here and nothing uses it; see the section
     * comment. UI thread. Never throws.
     */
    void sleepTelegramIfUnused() {
        try {
            if (telegramState != TG_AWAKE || !wokenFromSleep || telegramUsedHere) return;   // volatile reads: cheap
            if (telegramScreenOpen() || telegramInUse()) return;
        } catch (Throwable t) {
            fail("sleepTelegram", t);   // unsure: stay awake
            return;
        }
        synchronized (telegramLock) {
            if (telegramState != TG_AWAKE || telegramUsedHere) return;
            telegramState = TG_ASLEEP;   // isNetworkOnline answers false from here on
            pausedByUs = true;           // set first: a failure below wakes and unpauses
        }
        try {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                final ConnectionsManager cm = ConnectionsManager.getInstance(a);
                cm.checkConnection();                      // "no network": no new socket, no DNS fallback
                ConnectionsManager.native_pauseNetwork(a);  // Telegram's background pause
                dropSockets(cm);                           // and close what is open now
            }
            watchResumes();
            debugLog("asleep again");
        } catch (Throwable t) {
            fail("sleepTelegram", t);
            try {
                wakeTelegram();   // back to exactly Telegram's behaviour
            } catch (Throwable t2) {
                fail("sleepTelegram.undo", t2);
            }
        }
    }

    /**
     * Closes every open Telegram connection now, with Telegram's own discardConnection (tgnet suspends it; with "no
     * network" set first, Connection::connect refuses to reopen it). The pause alone isn't enough: tgnet skips its sleep
     * while a salt or file request is pending, and a request can't finish once the network reads as off, so the login's
     * sockets stayed open (device test, 10 November 2026). Production DCs 1-5, every connection kind and slot.
     */
    private static void dropSockets(ConnectionsManager cm) {
        final int[] single = {ConnectionsManager.ConnectionTypeGeneric, CONNECTION_GENERIC_MEDIA, CONNECTION_TEMP,
                ConnectionsManager.ConnectionTypePush};
        for (int dc = 1; dc <= 5; dc++) {
            for (int type : single) cm.discardConnection(dc, type);
            for (int n = 0; n < 8; n++) cm.discardConnection(dc, ConnectionsManager.ConnectionTypeDownload | (n << 16));
            for (int n = 0; n < 4; n++) cm.discardConnection(dc, ConnectionsManager.ConnectionTypeUpload | (n << 16));
        }
    }

    // tgnet Defines.h ConnectionType values with no Java constant.
    private static final int CONNECTION_TEMP = 16;
    private static final int CONNECTION_GENERIC_MEDIA = 64;

    /**
     * Any of Telegram's activities resuming (LaunchActivity.onResume, a bubble, the screen coming on) calls
     * setAppPaused(false, ...), which cancels tgnet's pause before its 10 s countdown closes the sockets. While asleep
     * by our pause, pause again right after that resume has run. tgnet ignores a pause while already paused.
     * Registered once per process, on the first sleep; afterwards one volatile read per activity resume.
     */
    private void watchResumes() {
        if (resumeWatch || !(ApplicationLoader.applicationContext instanceof android.app.Application)) return;
        resumeWatch = true;
        ((android.app.Application) ApplicationLoader.applicationContext).registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if (telegramState == TG_ASLEEP) AndroidUtilities.runOnUIThread(SecuredHooks.this::pauseAgainIfAsleep);
            }

            @Override public void onActivityCreated(@NonNull Activity activity, android.os.Bundle state) {}
            @Override public void onActivityStarted(@NonNull Activity activity) {}
            @Override public void onActivityPaused(@NonNull Activity activity) {}
            @Override public void onActivityStopped(@NonNull Activity activity) {}
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull android.os.Bundle out) {}
            @Override public void onActivityDestroyed(@NonNull Activity activity) {}
        });
    }

    /** Posted after an activity resume: our pause again, only while still asleep by our own pause. Never throws. */
    private void pauseAgainIfAsleep() {
        try {
            synchronized (telegramLock) {
                if (telegramState != TG_ASLEEP || !pausedByUs) return;
            }
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                ConnectionsManager.native_pauseNetwork(a);
            }
        } catch (Throwable t) {
            fail("pauseAgain", t);   // still "no network": no new socket either way
        }
    }

    /**
     * A Telegram intro or login screen that is still in a stack and not closing, in any layout (phone, tablet layer or
     * right pane). Every destroy path clears a fragment's parent layout, so a closed one never counts. UI thread.
     */
    private boolean telegramScreenOpen() {
        for (BaseFragment f : telegramScreens.keySet()) {
            if (f != null && f.getParentLayout() != null && !f.isRemovingFromStack()) return true;
        }
        return false;
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
