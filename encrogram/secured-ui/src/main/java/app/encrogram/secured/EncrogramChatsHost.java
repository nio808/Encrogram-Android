package app.encrogram.secured;

import android.animation.Animator;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.ViewPagerActivity;

import android.animation.ValueAnimator;


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
    private final SecuredBackgroundPrompt background = new SecuredBackgroundPrompt(this);

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

    // Kept as fields: a method reference makes a new object each time, and clearing must match the one we set.
    private final SecuredUi.LinkListener linkListener = this::onLinkAvailable;
    private final SecuredUi.OpenChatListener openChatListener = this::onOpenChatAvailable;

    @Override
    public boolean onFragmentCreate() {
        SecuredUi.setLinkListener(linkListener);
        SecuredUi.setOpenChatListener(openChatListener);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        SecuredUi.clearLinkListener(linkListener);
        SecuredUi.clearOpenChatListener(openChatListener);
        background.stop();
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
        onLinkAvailable();
        onOpenChatAvailable();
        if (selected == POSITION_SECURED) {
            background.arm();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        resumed = false;
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        removeDoors();
    }

    /**
     * After a Telegram login from the welcome screen or the standalone Secured list, that door stays below the main
     * tabs (LoginActivity replaces only itself). Remove it so Back never lands on a logged-out screen.
     */
    private void removeDoors() {
        final INavigationLayout layout = getParentLayout();
        if (layout == null || layout.getFragmentStack() == null) return;
        for (BaseFragment f : new ArrayList<>(layout.getFragmentStack())) {
            if (f instanceof SecuredWelcomeActivity
                    || f instanceof SecuredDialogsActivity && ((SecuredDialogsActivity) f).isStandalone()) {
                layout.removeFragmentFromStack(f);
            }
        }
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
            background.arm();
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
        // Over a Secured screen (a chat) the sheet shows at once; over a Telegram screen the link waits for onResume.
        if (viewPager == null || !((resumed && !isPaused()) || securedPage.canShowLinkNow())) {
            return;
        }
        final String link = SecuredUi.takePendingLink();
        if (link == null) {
            return;
        }
        securedPage.setPendingLink(link);
        if (selected == POSITION_SECURED || !resumed) {
            securedPage.showPendingLinkIfReady();
        } else {
            selectNetwork(POSITION_SECURED);   // the page shows the connect sheet when it resumes
        }
    }

    // ---- notification taps -----------------------------------------------------------------------------------

    private void onOpenChatAvailable() {
        // No "resumed" check: a tap must open the chat even while another screen (another chat) is on top.
        if (viewPager == null) {
            return;
        }
        final Long chatId = SecuredUi.takePendingOpenChat();
        if (chatId == null) {
            return;
        }
        selectNetwork(POSITION_SECURED);
        if (chatId != SecuredUi.NO_CHAT) {
            // Same call as a tap on a row of the Secured list: right-hand pane on tablets.
            presentFragment(new INavigationLayout.NavigationParams(new SecuredChatActivity(chatId).inRightPane()).forceRightLayout());
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
