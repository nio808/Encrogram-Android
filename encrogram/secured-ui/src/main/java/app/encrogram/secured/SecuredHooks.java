package app.encrogram.secured;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
import org.telegram.messenger.EncrogramHooks;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

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
     * No Telegram account: the id stays pending (in memory) and Telegram carries on; step 4 routes that case.
     */
    @Override
    public boolean handleIntent(Activity activity, Intent intent) {
        try {
            if (intent == null || !SecuredUi.ACTION_OPEN_CHAT.equals(intent.getAction())) {
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
            return UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated();
        } catch (Throwable t) {
            fail("handleIntent", t);
            return false;
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
