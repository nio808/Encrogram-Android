/*
 * Encrogram: the hook registry for the few places where Telegram's own code calls into Encrogram.
 *
 * Every method has a no-op default, so Telegram behaves exactly as upstream unless the Encrogram app module
 * installs an implementation at startup (EncrogramApplication -> SecuredUi.install). This file is new (it does
 * not exist upstream) and therefore never conflicts on a merge. Call sites are one line each, marked
 * "ENCROGRAM-HOOK(<name>)". All logic lives in :encrogram-secured-ui.
 *
 * Licensed under GNU GPL v. 2 or later, like the rest of Telegram for Android.
 */

package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;

import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.DialogsActivity;

public final class EncrogramHooks {

    private EncrogramHooks() {}

    public interface Hooks {

        /**
         * Chats tab: given Telegram's DialogsActivity, return the fragment to put at the Chats position.
         * The default returns it unchanged. Encrogram returns a two-page container (Telegram | Secured).
         */
        default BaseFragment wrapChats(DialogsActivity chats) {
            return chats;
        }

        /**
         * DialogsActivity action-bar title. Return the text to use as the title; Encrogram installs the
         * "Telegram | Secured" control in the bar's title container and returns a same-width blank title.
         */
        default CharSequence chatsTitle(DialogsActivity fragment, ActionBar actionBar, CharSequence title) {
            return title;
        }

        /**
         * Telegram's connection state ("Connecting...", "Waiting for network") normally replaces the title.
         * Return the text to show there; Encrogram returns null and shows the state on the Telegram segment.
         */
        default String routeTitleOverlay(BaseFragment fragment, String title, int titleId) {
            return title;
        }

        /**
         * DialogStoriesCell draws its own title (Telegram logo). Return the view to use for it; Encrogram
         * returns the same "Telegram | Secured" control. The default returns {@code logo} unchanged.
         */
        default ImageView storiesTitleView(Context context, BaseFragment fragment, ImageView logo) {
            return logo;
        }

        /** DialogStoriesCell swaps the logo for a text title. Encrogram keeps its control visible (returns 0). */
        default float storiesTitleVisibility(View logo, float titleVisibility) {
            return titleVisibility;
        }

        /**
         * LaunchActivity.handleIntent, before any Telegram account switch. Return true when Encrogram handled the
         * intent (for example a tap on a Secured notification). The default returns false: Telegram handles it.
         */
        default boolean handleIntent(Activity activity, Intent intent) {
            return false;
        }

        /**
         * LaunchActivity's start screen when no Telegram account is activated (first start and after the last
         * logout). The default returns Telegram's intro unchanged. Encrogram returns a welcome screen with both
         * doors: "Continue with Telegram" (which opens {@code intro}) and "Continue without Telegram".
         */
        default BaseFragment loggedOutIntro(BaseFragment intro) {
            return intro;
        }
    }

    private static final Hooks DEFAULT = new Hooks() {};
    private static volatile Hooks current = DEFAULT;

    public static Hooks get() {
        return current;
    }

    /** Called once by the Encrogram application at startup. Passing null restores the no-op default. */
    public static void install(Hooks hooks) {
        current = hooks != null ? hooks : DEFAULT;
    }
}
