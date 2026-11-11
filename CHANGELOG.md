# Changelog

Released versions stay supported: the API only grows, and every released version's requests are replayed
against the server on every change. Features added later may be missing in an older version; nothing it
uses breaks.

## 0.6.0

- Link buttons (block `links`): an optional Markdown intro and 1–3 outlined buttons. A tap reports it
  (`POST /v1/conversations/{id}/taps`, fire-and-forget) and opens the link with the OS, so the app's own deep-link
  handling runs. Only `https://` links and the app's own schemes (config `link_schemes`) open; other buttons don't show.
- Markdown links may also use the app's own schemes from `link_schemes` (other custom schemes stay plain text).
- Gifts (block `gift`): a card with a wrapped box, "A gift for you", the title, one line and "Open gift", which
  reports the opening (`POST /v1/gifts/{id}/opened`) and opens the gift's link. After `expires_at` it says
  "This gift has expired" (also while on screen).
- Several questions in one card (block `form`): single choice, multiple choice ("Pick any") and free text ("Your
  answer"); a required one left empty says "Please answer this one."; "Send answers" sends them all once
  (`{"form_answer": …}`), then the card shows the answers read-only. Answered elsewhere (409): it reloads; refused
  (400): the card opens again with the server's reason.
- Offers: a team message marked `promotional` shows an Offer tag and "Don't send me offers" (`PATCH /v1/me
  {"no_promotions": true}`), then "You won't get offers. Undo".
- The notification permission (`allowed`, `denied`, `not_asked`) goes to the team at configure and whenever the app
  comes back to the foreground, only when it changed for this install (`PATCH /v1/install {"push_permission"}`).
  Android 13+: not granted counts as `denied` once DevReply asked, `not_asked` before; below 13 it's
  `areNotificationsEnabled()`.
- Removed: the "Start a new conversation" button under a conversation a start tile continued. The tile still opens
  the topic's open conversation; the user writes there or goes back. `present(context, category, …)` still starts a
  new one.
- Every new block has `min_sdk` 0.6.0 and a text version: older versions show the text, and blocks newer than this
  one still fall back to theirs.

## 0.5.1

- The message box always appears: the profile (`GET /v1/me`) loads on its own, never behind the messages, and
  retries (1 s, 2 s, 5 s… then every 30 s) while the chat is on screen, at once when it comes back. Meanwhile the
  bottom shows a spinner, then "You seem to be offline." / "Something went wrong." with Try again; never empty.
  Home's list and config update even when only the profile fails.
- An unread reply comes first: home shows "New reply from {team}" with the reply's first words above "Start a
  conversation"; a tap opens it. A start tile whose topic has an open conversation opens that conversation, with
  "Start a new conversation" one tap away. `present(context, category, message, …)` still starts a new one.
- Every request has a 25 s deadline (120 s for uploads) on top of the socket timeouts: a server that hangs or
  trickles bytes now shows home's "can't connect" with Try again, like a refused connection.
- Image downloads time out (15 s to connect, 30 s to read) instead of waiting for ever.

## 0.5.0

- 34 languages (adds Arabic, Catalan, Croatian, Czech, Danish, Finnish, Hebrew, Hindi, Hungarian, Indonesian, Malay,
  Norwegian, European Portuguese, Romanian, Slovak, Swedish, Thai, Vietnamese, Traditional Chinese). Hebrew and Arabic
  lay the chat out right to left. Old language codes (iw, in, no) are understood.
- `present(…, askName = false)`: no name form while that messenger is open (e.g. from a failed purchase); the chat
  goes straight to the composer. The email ask after the first message stays.
- Button replies (0.5.0): a team question with 2–5 answer buttons under it; a tap sends the label as the user's
  message with the answer, the chosen button stays highlighted and the others go quiet (older versions show the text version).
- Same device after logout (0.5.0): a random device key, kept encrypted through `logout()`, goes with each install
  registration; when `login` gets the same account's earlier chats back on this device, the list and the open chat reload.
- Live updates (0.5.0): while the messenger is on screen, replies arrive over a WebSocket the moment they're sent
  (a small built-in client, no dependencies); it reconnects and resumes after a drop, and the 3 s poll runs only while it's down.
- Replies from the team and agents in Markdown (the `markdown` block, `min_sdk` 0.5.0): bold, italic, strike, code,
  links, headings, lists, code blocks and quotes, drawn natively. Older versions show the plain-text fallback.
- The unread bubble wears DevReply's new logo: the "Tilt" star on a black circle with a pink shadow.
- DevReply's notifications use the star as their icon.

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
