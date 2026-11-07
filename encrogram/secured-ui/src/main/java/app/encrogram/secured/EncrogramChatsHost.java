package app.encrogram.secured;

import android.animation.Animator;
import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.ViewPagerActivity;

import android.animation.ValueAnimator;

import app.encrogram.core.api.EngineState;
import app.encrogram.core.api.SecuredEngine;
import app.encrogram.core.api.SecuredListener;
import app.encrogram.core.background.BatteryHelper;
import app.encrogram.core.background.SecuredBackground;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The Chats tab with two pages: Telegram's own DialogsActivity (untouched) and our SecuredDialogsActivity.
 *
 * Switching is tap only (swiping is disabled, so Telegram's folder swipes and the bottom-tab swipes are not
 * affected) and uses a fade-through instead of a slide: a slide would mean "next folder", a fade says "a
 * different place". MainTabsActivity still talks to the inner DialogsActivity directly; this class only hosts it.
 */
public class EncrogramChatsHost extends ViewPagerActivity implements MainTabsActivity.TabFragmentDelegate, ChatsSwitcherView.Owner {

    private static final int POSITION_TELEGRAM = ChatsSwitcherView.NETWORK_TELEGRAM;
    private static final int POSITION_SECURED = ChatsSwitcherView.NETWORK_SECURED;

    private final DialogsActivity telegramPage;
    private final SecuredDialogsActivity securedPage;
    private final Set<ChatsSwitcherView> switchers = Collections.newSetFromMap(new WeakHashMap<>());

    private int selected = POSITION_TELEGRAM;
    private String telegramStatus;
    private int securedUnread;
    private boolean resumed;
    private Runnable onDestroy;
    private SecuredEngine bgEngine;
    private SecuredListener bgListener;

    EncrogramChatsHost(DialogsActivity telegramPage) {
        super();
        this.telegramPage = telegramPage;
        this.securedPage = new SecuredDialogsActivity(this);
        putFragmentAtPosition(POSITION_TELEGRAM, telegramPage);
        putFragmentAtPosition(POSITION_SECURED, securedPage);
    }

    /** Lets the hook registry drop its entry for this host when the fragment is destroyed. */
    void setOnDestroy(Runnable onDestroy) {
        this.onDestroy = onDestroy;
    }

    // ---- pages -----------------------------------------------------------------------------------------------

    @Override
    protected int getStartPosition() {
        return POSITION_TELEGRAM;   // always start on Telegram; the Secured engine starts only when Secured is shown
    }

    @Override
    protected int getFragmentsCount() {
        return 2;
    }

    @Override
    protected BaseFragment createBaseFragmentAt(int position) {
        return position == POSITION_SECURED ? securedPage : telegramPage;
    }

    @Override
    protected boolean canScrollBackward(MotionEvent ev) {
        return false;
    }

    @Override
    protected boolean canScrollForward(MotionEvent ev) {
        return false;
    }

    @Override
    public boolean onFragmentCreate() {
        SecuredUi.setLinkListener(this::onLinkAvailable);
        SecuredUi.setOpenChatListener(this::onOpenChatAvailable);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        SecuredUi.setLinkListener(null);
        SecuredUi.setOpenChatListener(null);
        stopBackgroundSetup();
        final Runnable release = onDestroy;
        onDestroy = null;
        if (release != null) {
            release.run();
        }
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        SecuredUi.debugSelfCheck(getParentActivity(), currentAccount);
        onLinkAvailable();
        onOpenChatAvailable();
        if (selected == POSITION_SECURED) {
            armBackgroundSetup();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        resumed = false;
    }

    // ---- switching (Owner) -----------------------------------------------------------------------------------

    @Override
    public int selectedNetwork() {
        return selected;
    }

    @Override
    public void selectNetwork(int network) {
        if (network == selected || viewPager == null) {
            return;
        }
        selected = network;
        if (network == POSITION_SECURED) {
            armBackgroundSetup();
        }
        for (ChatsSwitcherView v : new ArrayList<>(switchers)) {
            v.setSelectedAnimated(network);
        }
        fadeThrough(network);
    }

    private void fadeThrough(int position) {
        viewPager.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            viewPager.setAlpha(1f);
            viewPager.setScaleX(1f);
            viewPager.setScaleY(1f);
            viewPager.setPosition(position);
            return;
        }
        viewPager.animate().alpha(0f).setDuration(90).withEndAction(() -> {
            viewPager.setPosition(position);
            viewPager.setScaleX(0.96f);
            viewPager.setScaleY(0.96f);
            viewPager.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }).start();
    }

    @Override
    public String telegramStatus() {
        return telegramStatus;
    }

    void setTelegramStatus(String status) {
        telegramStatus = status;
        for (ChatsSwitcherView v : new ArrayList<>(switchers)) {
            v.setTelegramStatus(status);
        }
    }

    @Override
    public int securedUnread() {
        return securedUnread;
    }

    void setSecuredUnread(int count) {
        securedUnread = count;
        for (ChatsSwitcherView v : new ArrayList<>(switchers)) {
            v.setSecuredUnread(count);
        }
    }

    @Override
    public void attachSwitcher(ChatsSwitcherView view) {
        switchers.add(view);
    }

    @Override
    public void detachSwitcher(ChatsSwitcherView view) {
        switchers.remove(view);
    }

    // ---- deep links ------------------------------------------------------------------------------------------

    private void onLinkAvailable() {
        if (!resumed || viewPager == null) {
            return;
        }
        final String link = SecuredUi.takePendingLink();
        if (link == null) {
            return;
        }
        securedPage.setPendingLink(link);
        if (selected == POSITION_SECURED) {
            securedPage.showPendingLinkIfReady();
        } else {
            selectNetwork(POSITION_SECURED);   // the page shows the connect sheet when it resumes
        }
    }

    // ---- notification taps -----------------------------------------------------------------------------------

    private void onOpenChatAvailable() {
        if (!resumed || viewPager == null) {
            return;
        }
        final Long chatId = SecuredUi.takePendingOpenChat();
        if (chatId == null) {
            return;
        }
        selectNetwork(POSITION_SECURED);
        if (chatId != SecuredUi.NO_CHAT) {
            // Same call as a tap on a row of the Secured list: right-hand pane on tablets.
            presentFragment(new INavigationLayout.NavigationParams(new SecuredChatActivity(chatId)).forceRightLayout());
        }
    }

    // ---- background receiving: first use ----------------------------------------------------------------------

    private static final int REQUEST_POST_NOTIFICATIONS = 7301;   // Telegram's codes: 2..22, 104, 150..211
    private static final String UI_PREFS = "encrogram_secured_ui";
    private static final String KEY_BG_ASKED = "background_asked";

    /** Waits (through a listener, which replays the state at once) for the engine to be RUNNING with a profile. */
    private void armBackgroundSetup() {
        if (bgListener != null) {
            return;
        }
        try {
            final SecuredEngine engine = SecuredUi.engine();
            if (engine == null) {
                return;
            }
            bgEngine = engine;
            bgListener = new SecuredListener() {
                @Override
                public void onStateChanged(EngineState state) {
                    if (state == EngineState.RUNNING && engine.profile() != null) {
                        // Not inside the engine's own callback (it replays the state while addListener runs).
                        AndroidUtilities.runOnUIThread(() -> {
                            if (bgListener != null) {
                                stopBackgroundSetup();
                                setUpBackground();
                            }
                        });
                    }
                }
            };
            engine.addListener(bgListener);
        } catch (Throwable t) {
            stopBackgroundSetup();
        }
    }

    private void stopBackgroundSetup() {
        try {
            if (bgEngine != null && bgListener != null) {
                bgEngine.removeListener(bgListener);
            }
        } catch (Throwable ignored) {
        }
        bgEngine = null;
        bgListener = null;
    }

    /** Starts the service (every time, it is idempotent) and, once ever, asks for notifications and battery. */
    private void setUpBackground() {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        try {
            SecuredBackground.start(activity);
            final android.content.SharedPreferences prefs = activity.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE);
            if (prefs.getBoolean(KEY_BG_ASKED, false)) {
                return;
            }
            prefs.edit().putBoolean(KEY_BG_ASKED, true).apply();
            if (BatteryHelper.isIgnoringBatteryOptimizations(activity)) {
                requestNotificationPermission(activity);
                return;
            }
            final AlertDialog.Builder b = new AlertDialog.Builder(activity, SecuredResourcesProvider.get());
            b.setTitle(activity.getString(R.string.sec_bg_title));
            b.setMessage(activity.getString(R.string.sec_bg_text));
            b.setPositiveButton(activity.getString(R.string.sec_bg_allow), (d, w) -> {
                try {
                    activity.startActivity(BatteryHelper.requestIgnoreIntent(activity));
                } catch (Throwable ignored) {
                }
                requestNotificationPermission(activity);
            });
            b.setNegativeButton(activity.getString(R.string.sec_bg_not_now), (d, w) -> requestNotificationPermission(activity));
            showDialog(b.create());
        } catch (Throwable ignored) {
        }
    }

    private static void requestNotificationPermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= 33
                && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
        }
    }

    // ---- delegate plumbing for MainTabsActivity ---------------------------------------------------------------

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        final BaseFragment f = getCurrentVisibleFragment();
        return f instanceof MainTabsActivity.TabFragmentDelegate
                && ((MainTabsActivity.TabFragmentDelegate) f).canParentTabsSlide(ev, forward);
    }

    @Override
    public void onParentScrollToTop() {
        final BaseFragment f = getCurrentVisibleFragment();
        if (f instanceof MainTabsActivity.TabFragmentDelegate) {
            ((MainTabsActivity.TabFragmentDelegate) f).onParentScrollToTop();
        }
    }

    @Override
    public BlurredBackgroundSourceRenderNode getGlassSource() {
        final BaseFragment f = getCurrentVisibleFragment();
        return f instanceof MainTabsActivity.TabFragmentDelegate
                ? ((MainTabsActivity.TabFragmentDelegate) f).getGlassSource() : null;
    }

    @Override
    public boolean canBeginSlide() {
        final BaseFragment f = getCurrentVisibleFragment();
        return f != null && f.canBeginSlide();
    }

    @Override
    public void onBeginSlide() {
        super.onBeginSlide();
        final BaseFragment f = getCurrentVisibleFragment();
        if (f != null) f.onBeginSlide();
    }

    @Override
    public void onSlideProgress(boolean isOpen, float progress) {
        final BaseFragment f = getCurrentVisibleFragment();
        if (f != null) f.onSlideProgress(isOpen, progress);
    }

    @Override
    public Animator getCustomSlideTransition(boolean topFragment, boolean backAnimation, float distanceToMove) {
        final BaseFragment f = getCurrentVisibleFragment();
        return f != null ? f.getCustomSlideTransition(topFragment, backAnimation, distanceToMove) : null;
    }

    @Override
    public void prepareFragmentToSlide(boolean topFragment, boolean beginSlide) {
        final BaseFragment f = getCurrentVisibleFragment();
        if (f != null) f.prepareFragmentToSlide(topFragment, beginSlide);
    }
}
