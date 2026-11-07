# :encrogram-secured-ui

Android library (Java). Depends on `:TMessagesProj` (look and scaffolding only) and `:encrogram-core` (the `SecuredEngine` contract).
All Secured screens and the "Telegram | Secured" control live here. Telegram's own code only calls in through `EncrogramHooks`.

## What is in it (`src/main/java/app/encrogram/secured`)
- `SecuredUi` - entry point: `install(app, engineProvider)` registers the hooks, owns the single engine, passes deep links.
- `SecuredHooks` - implements `EncrogramHooks.Hooks` (the targets of the upstream one-liners).
- `EncrogramChatsHost` - two-page Chats container: Telegram's `DialogsActivity` (untouched) and `SecuredDialogsActivity`. Tap-only fade-through switching.
- `ChatsSwitcherView` (+ `LockIcon`) - the "Telegram | Secured" pill, 36 dp in a 48 dp touch area, TalkBack labels per tab.
- `SecuredDialogsActivity` - profile setup, engine states, chat list (DialogCell via CustomDialog), empty state, FAB.
- `SecuredSheets` - invite sheet (Copy, Share) and connect sheet (checkLink, readable errors).
- `SecuredChatActivity`, `BubbleView`, `DateView` - chat screen: own bubbles, ticks, composer from `EditTextCaption` + `ChatActivityEnterView.SendButton`.
- `SecuredResourcesProvider` - Encro Indigo overrides, Secured screens only. `Kit` - small view helpers.
- Strings: `res/values/secured_strings.xml` (plain Android resources).

## How it connects
`:Encrogram_App` -> `EncrogramApplication.onCreate` -> `SecuredUi.install` -> `EncrogramHooks.install(SecuredHooks)`.
Upstream hooks (see `encrogram/app/README.md` for file:line list) call `wrapChats`, `chatsTitle`, `routeTitleOverlay`, `storiesTitleView`, `storiesTitleVisibility`.
The engine is created and started lazily when the Secured page is first resumed.

## TODO for follow-up micro tasks
- Compile and fix `:encrogram-secured-ui` once `:encrogram-core` compiles (code was written without a successful compile) - all files.
- Visual check of the switcher in the DialogsActivity title and the stories title (position sync, emoji status, RTL, large fonts) - ChatsSwitcherView.java, SecuredHooks.java.
- Verify `SendButton` renders and animates correctly outside ChatActivityEnterView - SecuredChatActivity.java.
- Telegram unread counter on the Telegram segment - ChatsSwitcherView.java.
- Chats-tab `openFolder` should switch back to Telegram - needs a hook in MainTabsActivity (budget) - EncrogramChatsHost.java.
- Deep link only handled when Chats tab is resumed; handle other tabs/screens - EncrogramChatsHost.java.
- Secured is unreachable without a Telegram login (no startupRoute hook yet) - SecuredHooks.java.
- Telegram "Connecting..." tap action (proxy setup) is dropped when routed to the dot - SecuredHooks.java.
- "N stories" title is suppressed while the control is shown - SecuredHooks.java.
- Disable text-style formatting in `EditTextCaption` (uses MediaDataController locally) - SecuredChatActivity.java.
- Message long-press menu only has Copy; no reply/delete/info - SecuredChatActivity.java.
- Verified badge / shield, avatar images, typing line, swipe actions in the list - SecuredDialogsActivity.java.
- Secured search (local only) - SecuredDialogsActivity.java.
- Insets: bottom padding uses AndroidUtilities.navigationBarHeight, not live insets - SecuredDialogsActivity.java.
- Share-sheet exclusion of LaunchActivity should be checked on a device - SecuredSheets.java.
- Notifications, background receiving, Secured lock, groups/files/voice: out of MVP.
