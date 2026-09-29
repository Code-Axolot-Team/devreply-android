# Changelog

Released versions stay supported: the API only grows, and every released version's requests are replayed
against the server on every change. Features added later may be missing in an older version; nothing it
uses breaks.

## 0.4.4

* `DevReply.present(context, category, message, attributes)`: `message` prefills the composer of the new
  conversation (the user sees it and can edit it; never sent by itself); `attributes` go with that conversation
  as its context, shown to your team (text, number or true/false, up to 20). Returns `false` and shows nothing
  when DevReply isn't configured or the chat is switched off; existing calls keep compiling.
* The chat's on/off switch in the dashboard: `DevReply.isAvailable` (Compose state). While it's off, `present`
  returns `false`, the unread bubble hides, `handlePush` still takes DevReply's messages but shows nothing, and
  an open messenger closes. Login, logout, attributes and pushes keep working.
* `DevReply.deleteUser()` never gives up: offline or on a server error, the device forgets the user at once
  and DevReply retries the deletion (with the old install's token, stored encrypted) at every `configure` and
  whenever the app comes back, until the server confirms. Returns `true` when deleted now, `false` when queued.
* Events for analytics: `DevReply.addEventListener { event -> }` with `MessengerOpened`, `MessengerClosed`,
  `ConversationStarted(conversationId, category)` and `MessageSent(conversationId)`, on the main thread;
  `cancel()` the returned subscription.
* Dark mode, opt-in: `DevReply.darkTheme = DevReplyTheme.Dark` (or your own colours) is used when the
  configuration is in night mode (the system's or your app's). Without it the chat stays light, exactly as
  before. `DevReplyTheme.Dark` is "Deep blue" (navy page, cobalt header, pink buttons). A dark theme takes the
  same six colours as the light one; DevReply derives everything else (cards a step above the page, thin light
  outlines, dark shadows, black or white text on buttons, lemon for the small brand touches) and draws its own
  dark category icons. Every colour in the chat now follows the theme, including the attach menu, the photo
  viewer, the unread bubble, text selection, DevReply's notification colour and the status bar icons.

## 0.4.3

* Signed-in users: `DevReply.login(userId)` after sign-in (your own id for the user; the team sees it, your
  backend can delete the user by it), `DevReply.logout()` on every sign-out (the device forgets the chat; the
  next person starts empty), `DevReply.deleteUser()` (suspend, or `deleteUser { ok -> }`) in your
  delete-account flow (deletes the user's data, then logs out).
* `DevReply.handleNotificationOpened(context, data)` for DevReply notifications your own push code showed.
  DevReply's own notifications (from `handlePush`) open the conversation by themselves; the first tap is
  reported so the dashboard shows "Taps open the chat".

## 0.4.2

* Push notifications, like Intercom: your app keeps its Firebase Cloud Messaging setup and passes DevReply
  the token (`DevReply.registerPush(context, token)`) and DevReply's messages
  (`DevReply.handlePush(context, message.data)` first in `onMessageReceived`). The SDK shows its own
  notification (who replied, the reply, a "Replies" channel, icon `devreply_push_icon` you can override);
  a tap opens the conversation over the app. The chat offers to turn notifications on after the user's
  first message (Android 13+ permission); "Not now" hides it for 3 days. The SDK doesn't depend on Firebase.

## 0.4.0

* Replies show who wrote them: the teammate's name, title and photo, once per group of replies.
* The app's icon in the chat's header; the team's faces on the chat's home screen.
* Links from DevReply's emails ("Reply in the app") open the right conversation: `DevReply.handle(context, uri)`.
* A refused public key isn't retried in a loop.
* The chat speaks the user's language (15 languages, the device's by default); `DevReply.setLocale("es")`.
* Reply times are presets, translated in the chat.

## 0.3.0 – 0.3.2

* The first releases: the native chat in Jetpack Compose (home with start buttons, conversations, photos
  and files, name first, optional email, "we got it" with the reply time), the unread bubble, `setUser`,
  `setAttributes`, `unreadCount`, `theme`.
