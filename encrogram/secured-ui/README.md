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
- `SecuredChatActivity` (+ `SecuredMessageMapper`, `SecuredChatResources`, `SecuredReactionsView`) - chat screen: Telegram's `ChatMessageCell` through the one text mapper, ticks, reactions, composer from `EditTextCaption` + `ChatActivityEnterView.SendButton`.
- `SecuredWelcomeActivity` - logged-out start screen (`SecuredHooks.loggedOutIntro`): "Continue with Telegram" opens Telegram's intro on top (Back returns), "Continue without Telegram" saves Secured-only in our own prefs and replaces it with the standalone list.
- Standalone mode: `new SecuredDialogsActivity()` (no host) is the root screen when Telegram is logged out. Its switcher is satisfied locally (Secured selected; tapping Telegram opens login above it). It consumes deep links and notification taps itself; `SecuredBackgroundPrompt` is the first-use background setup shared with `EncrogramChatsHost`.
- `SecuredResourcesProvider` - Encro Indigo overrides, Secured screens only. `Kit` - small view helpers.
- Strings: `res/values/secured_strings.xml` (plain Android resources).

## How it connects
`:Encrogram_App` -> `EncrogramApplication.onCreate` -> `SecuredUi.install` -> `EncrogramHooks.install(SecuredHooks)`.
Upstream hooks (see `encrogram/app/README.md` for file:line list) call `wrapChats`, `chatsTitle`, `routeTitleOverlay`, `storiesTitleView`, `storiesTitleVisibility`.
The engine is created and started lazily when the Secured page is first resumed.

## Open items
Open work lives in `docs/tasks.md` (workspace root) and `research/gaps-audit.md`; the old TODO list here was
written before the first compile and is gone. Still open in this module: media (step 5), the composer's emoji
panel, partial text selection, message info, and a device check of the share-sheet exclusion.
