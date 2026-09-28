package com.devreply.sdk.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devreply.sdk.Block
import com.devreply.sdk.DevReply
import com.devreply.sdk.DevReplyCategory
import com.devreply.sdk.DevReplyError
import com.devreply.sdk.Message
import com.devreply.sdk.Messenger
import com.devreply.sdk.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Everything the thread shows, oldest first: time labels, messages, sends in progress. */
private sealed interface ChatItem {
    val key: String

    data class Time(val at: Instant, override val key: String) : ChatItem
    data class Msg(val message: Message) : ChatItem { override val key get() = message.id.toString() }
    data class Pend(val item: ConversationModel.Pending) : ChatItem { override val key get() = "pending-${item.id}" }

    /** Under the user's first message: we got it, please allow up to <reply time>. */
    data object Notice : ChatItem { override val key get() = "notice" }
}

@Composable
internal fun ConversationScreen(conversationId: UUID?, category: DevReplyCategory?, back: () -> Unit) {
    val model = remember { ConversationModel(conversationId, category) }
    val theme = DevReply.theme
    val config = Messenger.config
    val haptics = LocalHapticFeedback.current
    var viewing by remember { mutableStateOf<String?>(null) }
    // Started on this screen (not opened from the list): the email ask belongs to its first message only.
    val startedHere = remember { conversationId == null }
    var emailAskDone by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val hideKeyboardAfter = with(LocalDensity.current) { 90.dp.toPx() }

    LaunchedEffect(model.conversationId) {
        Messenger.loadProfileIfNeeded()
        while (true) {
            model.load()
            delay(3_000)
        }
    }
    DisposableEffect(model.conversationId) {
        Messenger.visibleConversation = model.conversationId
        onDispose { Messenger.visibleConversation = null }
    }
    LaunchedEffect(model.sentCount) {
        if (model.sentCount > 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val firstFromUser = model.messages.indexOfFirst { it.isFromUser }
    val items = buildList {
        model.messages.forEachIndexed { i, m ->
            val gap = i == 0 || Duration.between(model.messages[i - 1].createdAt, m.createdAt).toMinutes() > 15
            if (gap) add(ChatItem.Time(m.createdAt, "time-${m.id}"))
            add(ChatItem.Msg(m))
            if (i == firstFromUser) add(ChatItem.Notice)
        }
        model.pending.forEach { add(ChatItem.Pend(it)) }
    }

    // The list keeps its place by item key, so a new message (sent or received) would land below the
    // newest one on screen, behind the composer and keyboard. If the user is at the bottom, follow it;
    // if they scrolled up into the history, leave them there.
    val listState = rememberLazyListState()
    val newest = items.lastOrNull()?.key
    LaunchedEffect(newest) {
        if (newest != null && listState.firstVisibleItemIndex <= 3) listState.animateScrollToItem(0)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars)),
    ) {
        TopBar(back)
        // A long drag down through the thread hides the keyboard, like any good chat. Watched without
        // consuming anything, so the list still scrolls as usual.
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.position.y - down.position.y > hideKeyboardAfter) {
                            keyboard?.hide()
                            focusManager.clearFocus()
                        }
                        if (!change.pressed) break
                    }
                }
            },
        ) {
            if (items.isEmpty()) {
                Intro(model.category ?: DevReplyCategory.Other)
            } else {
                // Inverted list: the bottom is the natural start, so new messages appear at the bottom
                // and push the rest up. Nothing ever scrolls in code.
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(items.asReversed(), key = { it.key }) { item ->
                        Box(Modifier.animateItem()) {
                            when (item) {
                                is ChatItem.Time -> TimeLabel(item.at)
                                is ChatItem.Msg -> MessageRow(item.message, config.teamName, onOpenImage = { viewing = it })
                                is ChatItem.Pend -> PendingRow(item.item, config.teamName) { model.retry(item.item) }
                                ChatItem.Notice -> ReceivedNotice(config.teamName, config.replyWithin, Messenger.profile?.email?.takeIf { it.isNotBlank() })
                            }
                        }
                    }
                }
            }
        }
        when {
            Messenger.profile == null -> Spacer(Modifier.height(1.dp))
            Messenger.needsName -> NameForm()
            else -> Column {
                val showsEmailAsk = startedHere && !emailAskDone && Messenger.profile?.email.isNullOrBlank() &&
                    model.messages.any { it.isFromUser }
                if (showsEmailAsk) EmailAskCard { emailAskDone = true }
                Composer(autoFocus = model.conversationId == null) { text, staged -> model.send(text, staged) }
            }
        }
    }

    viewing?.let { url -> ImageViewer(url) { viewing = null } }
}

@Composable
private fun TopBar(back: () -> Unit) {
    val theme = DevReply.theme
    val config = Messenger.config
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.primary)
            .drawBehind {
                val h = 3.dp.toPx()
                drawRect(theme.ink, topLeft = Offset(0f, size.height - h), size = size.copy(height = h))
            }
            .statusBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconSquareButton(R.drawable.devreply_ic_back, "Back", back, Modifier.testTag("devreply.back"))
        TeamAvatar(config.teamName, 32.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(config.teamName.ifEmpty { "Chat" }, style = display(16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (config.replyTime.isNotEmpty()) {
                BasicText(config.replyTime, style = text(11.sp, FontWeight.Medium, theme.ink.copy(alpha = 0.75f)), maxLines = 1)
            }
        }
    }
}

/** Shown while the conversation is empty, at the top of the screen. */
@Composable
private fun Intro(category: DevReplyCategory) {
    val title = Messenger.config.startButtons.firstOrNull { it.category == category }?.title ?: category.defaultTitle
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 36.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.brutal(shadow = 6.dp).padding(18.dp)) {
            Image(painterResource(category.icon), null, Modifier.size(64.dp))
        }
        BasicText(title, style = display(26.sp).copy(textAlign = TextAlign.Center))
        BasicText(category.prompt, style = text(16.sp, FontWeight.Medium, Brand.muted).copy(textAlign = TextAlign.Center))
    }
}

// MARK: Composer

@Composable
private fun Composer(autoFocus: Boolean, send: (String, List<Staged>) -> Unit) {
    val theme = DevReply.theme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var staged by remember { mutableStateOf(emptyList<Staged>()) }
    var pickError by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        pickError = null
        scope.launch {
            val results = withContext(Dispatchers.IO) { uris.map { Staged.from(context, it) } }
            for (r in results) {
                r.onSuccess { staged = staged + it }
                r.onFailure { e ->
                    pickError = when (val p = (e as? Staged.ProblemException)?.problem) {
                        is Staged.Problem.TooBig -> "${p.name} is over 10 MB."
                        else -> "Couldn't read that file."
                    }
                }
            }
            if (staged.size > 4) {
                staged = staged.take(4)
                pickError = "Up to 4 attachments per message."
            }
        }
    }

    // The system photo picker: no permission prompt. Files through the system file picker.
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { add(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }

    LaunchedEffect(Unit) {
        if (autoFocus) {
            delay(300) // after the slide-in
            runCatching { focus.requestFocus() }
        }
    }

    val canSend = draft.isNotBlank() || staged.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.White)
            .drawBehind { drawRect(theme.ink, size = size.copy(height = 3.dp.toPx())) }
            .padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (staged.isNotEmpty()) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp, end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                staged.forEach { item -> StagedThumb(item) { staged = staged.filter { it.id != item.id } } }
            }
        }
        pickError?.let { BasicText(it, style = text(13.sp, FontWeight.Bold, Brand.error)) }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                IconSquareButton(
                    R.drawable.devreply_ic_attach, "Attach a photo or file", { menu = true },
                    Modifier.testTag("devreply.attach"), size = 46.dp,
                )
                DropdownMenu(menu, { menu = false }, Modifier.background(Color.White)) {
                    DropdownMenuItem(
                        text = { BasicText("Photo", style = text(16.sp, FontWeight.Medium)) },
                        leadingIcon = { Image(painterResource(R.drawable.devreply_ic_photo), null, Modifier.size(22.dp)) },
                        onClick = {
                            menu = false
                            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    DropdownMenuItem(
                        text = { BasicText("File", style = text(16.sp, FontWeight.Medium)) },
                        leadingIcon = { Image(painterResource(R.drawable.devreply_ic_file), null, Modifier.size(22.dp)) },
                        onClick = {
                            menu = false
                            files.launch(arrayOf("*/*"))
                        },
                    )
                }
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
                    .background(Color.White)
                    .border(3.dp, theme.ink)
                    .focusRequester(focus)
                    .testTag("devreply.composer"),
                textStyle = text(17.sp),
                cursorBrush = SolidColor(theme.ink),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) BasicText("Message…", style = text(17.sp, color = Brand.muted.copy(alpha = 0.7f)))
                        inner()
                    }
                },
            )
            BrutalButton(
                onClick = {
                    send(draft, staged)
                    draft = ""
                    staged = emptyList()
                    pickError = null
                },
                modifier = Modifier.alpha(if (canSend) 1f else 0.45f).testTag("devreply.send"),
                fill = theme.accent,
                shadow = 3.dp,
                enabled = canSend,
                label = "Send",
            ) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.devreply_ic_send), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(theme.ink))
                }
            }
        }
    }
}

@Composable
private fun StagedThumb(item: Staged, remove: () -> Unit) {
    Box {
        Box(Modifier.size(64.dp).border(2.5.dp, Brand.ink).clipToBounds()) {
            if (item.preview != null) {
                Image(item.preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(
                    Modifier.fillMaxSize().background(Brand.lemon).padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(painterResource(R.drawable.devreply_ic_file), null, Modifier.size(18.dp))
                    BasicText(item.name, style = text(10.sp, FontWeight.Bold).copy(textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.matchParentSize().border(2.5.dp, Brand.ink))
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(7.dp, (-7).dp)
                .size(22.dp)
                .background(Brand.pink)
                .border(2.dp, Brand.ink)
                .clickable(onClick = remove)
                .semantics { contentDescription = "Remove ${item.name}" },
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.devreply_ic_close), null, Modifier.size(12.dp))
        }
    }
}

// MARK: Name first (spec 05)

/** Asked once, before the first message, unless the host app passed a name with `DevReply.setUser`. */
@Composable
private fun NameForm() {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameFocus = remember { FocusRequester() }
    val emailFocus = remember { FocusRequester() }

    fun save() {
        if (name.isBlank() || saving) return
        saving = true
        error = null
        scope.launch {
            try {
                Messenger.saveProfile(name.trim(), email.trim().ifEmpty { null })
            } catch (e: DevReplyError.Invalid) {
                val m = e.message ?: "invalid"
                error = m.replaceFirstChar { it.uppercase() } + "."
            } catch (e: Exception) {
                error = "Couldn't save. Check your connection and try again."
            }
            saving = false
        }
    }

    LaunchedEffect(Unit) {
        delay(300)
        runCatching { nameFocus.requestFocus() }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.lemon)
            .drawBehind { drawRect(Brand.ink, size = size.copy(height = 3.dp.toPx())) }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Kicker("Before we start", inverted = true)
        BasicText(
            "So the developer knows who they're talking to. Add your email to get the reply there too.",
            style = text(15.sp, FontWeight.Medium, Brand.ink),
        )
        FormField(
            name, { name = it }, "Your name",
            Modifier.focusRequester(nameFocus).testTag("devreply.profile.name"),
            KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            KeyboardActions(onNext = { emailFocus.requestFocus() }),
        )
        FormField(
            email, { email = it }, "Email (optional)",
            Modifier.focusRequester(emailFocus).testTag("devreply.profile.email"),
            KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            KeyboardActions(onDone = { save() }),
        )
        error?.let { BasicText(it, style = text(13.sp, FontWeight.Bold, Brand.error)) }
        BrutalButton(
            ::save,
            Modifier.fillMaxWidth().alpha(if (name.isBlank()) 0.5f else 1f).testTag("devreply.profile.save"),
            fill = Brand.pink,
            shadow = 4.dp,
            enabled = !saving && name.isNotBlank(),
        ) {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                BasicText(if (saving) "Saving…" else "Start chatting", style = text(16.sp, FontWeight.Bold, Brand.ink))
            }
        }
    }
}

@Composable
private fun FormField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    keyboard: KeyboardOptions,
    actions: KeyboardActions,
) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().height(46.dp).background(Color.White).border(2.5.dp, Brand.ink),
        textStyle = text(17.sp, color = Brand.ink),
        cursorBrush = SolidColor(Brand.ink),
        singleLine = true,
        keyboardOptions = keyboard,
        keyboardActions = actions,
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) BasicText(placeholder, style = text(17.sp, color = Brand.muted.copy(alpha = 0.7f)))
                inner()
            }
        },
    )
}

// MARK: After the first message (spec 05)

/**
 * Under the user's first message, so it never feels like writing into the void: it arrived, and how
 * long a reply usually takes (the app's own reply time, from the dashboard). Never promises who answers.
 */
@Composable
private fun ReceivedNotice(teamName: String, within: String, email: String?) {
    val body = "Please allow up to $within for a reply. " +
        (email?.let { "We'll also email you at $it." } ?: "You'll see it right here.")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, end = 4.dp)
            .brutal(shadow = 3.dp)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("devreply.notice"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TeamAvatar(teamName, 36.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText("Thanks, we got it!", style = text(15.sp, FontWeight.Bold, Brand.ink))
            BasicText(body, style = text(14.sp, FontWeight.Medium, Brand.ink))
        }
    }
}

/** Right after the first message of a request, if we don't have their email: optional, one tap to skip. */
@Composable
private fun EmailAskCard(done: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        if (email.isBlank() || saving) return
        saving = true
        error = null
        scope.launch {
            try {
                Messenger.saveProfile(null, email.trim())
                done()
            } catch (e: DevReplyError.Invalid) {
                error = (e.message ?: "invalid").replaceFirstChar { it.uppercase() } + "."
            } catch (e: Exception) {
                error = "Couldn't save. Check your connection and try again."
            }
            saving = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Brand.lemon)
            .drawBehind { drawRect(Brand.ink, size = size.copy(height = 3.dp.toPx())) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText("Get the reply by email too?", Modifier.weight(1f), style = text(15.sp, FontWeight.Bold, Brand.ink))
            BasicText(
                "No thanks",
                Modifier.clickable(onClick = done).padding(4.dp).testTag("devreply.emailask.skip"),
                style = text(14.sp, FontWeight.Bold, Brand.muted),
            )
        }
        BasicText(
            "Optional. Only about this conversation, and you can unsubscribe any time.",
            style = text(13.sp, FontWeight.Medium, Brand.ink),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                FormField(
                    email, { email = it }, "you@example.com",
                    Modifier.testTag("devreply.emailask.field"),
                    KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    KeyboardActions(onDone = { save() }),
                )
            }
            BrutalButton(
                ::save,
                Modifier.alpha(if (email.isBlank()) 0.5f else 1f).testTag("devreply.emailask.save"),
                fill = Brand.pink,
                shadow = 3.dp,
                enabled = !saving && email.isNotBlank(),
                label = "Save",
            ) {
                Box(Modifier.height(46.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    BasicText(if (saving) "…" else "Save", style = text(15.sp, FontWeight.Bold, Brand.ink))
                }
            }
        }
        error?.let { BasicText(it, style = text(13.sp, FontWeight.Bold, Brand.error)) }
    }
}

// MARK: Messages

/** "MON · 18:52", like the timestamps on devreply.com. */
@Composable
private fun TimeLabel(at: Instant) {
    val context = LocalContext.current
    val day = DateTimeFormatter.ofPattern("EEE", Locale.getDefault()).format(at.atZone(ZoneId.systemDefault()))
    val time = DateFormat.getTimeFormat(context).format(Date.from(at))
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        Kicker("$day · $time")
    }
}

/** Lays out one message: the user's on the right, the team's on the left with the avatar. */
@Composable
private fun ChatRow(fromUser: Boolean, teamName: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (fromUser) 48.dp else 0.dp, end = if (fromUser) 4.dp else 48.dp),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!fromUser) {
            TeamAvatar(teamName, 30.dp, fill = Brand.lemon)
            Spacer(Modifier.size(10.dp))
        }
        Column(
            Modifier.weight(1f, fill = false),
            horizontalAlignment = if (fromUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { content() }
    }
}

@Composable
private fun MessageRow(message: Message, teamName: String, onOpenImage: (String) -> Unit) {
    val context = LocalContext.current
    if (message.author == Message.Author.System) {
        // e.g. "✓ Marked as resolved…": a quiet line, not a bubble.
        BasicText(
            message.plainText,
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            style = text(13.sp, FontWeight.Bold, Brand.muted).copy(textAlign = TextAlign.Center),
        )
        return
    }
    ChatRow(message.isFromUser, teamName) {
        message.blocks.forEach { block ->
            when (block) {
                is Block.Text -> TextBubble(block.text, message.isFromUser)
                is Block.Image -> RemoteImage(block.url, block.width, block.height) { onOpenImage(block.url) }
                is Block.File -> BrutalButton(
                    { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(block.url))) } },
                    shadow = 3.dp,
                    label = "File ${block.name}",
                ) { FileChip(block.name, block.size) }
                is Block.Unsupported -> TextBubble(block.fallback, message.isFromUser, muted = true)
            }
        }
    }
}

@Composable
private fun PendingRow(item: ConversationModel.Pending, teamName: String, retry: () -> Unit) {
    Box(Modifier.then(if (item.failure != null) Modifier.clickable(onClick = retry) else Modifier)) {
        ChatRow(fromUser = true, teamName = teamName) {
            item.attachments.forEach { staged ->
                val preview = staged.preview
                if (preview != null) {
                    Image(
                        preview, null,
                        Modifier
                            .sizeIn(maxWidth = 220.dp, maxHeight = 260.dp)
                            .aspectRatio(preview.width.toFloat() / maxOf(preview.height, 1))
                            .border(3.dp, Brand.ink)
                            .alpha(0.7f),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.brutal(shadow = 3.dp).alpha(0.7f)) { FileChip(staged.name, staged.attachment.data.size) }
                }
            }
            if (item.text.isNotEmpty()) {
                Box(Modifier.alpha(if (item.failure == null) 0.7f else 1f)) { TextBubble(item.text, fromUser = true) }
            }
            if (item.failure != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Image(painterResource(R.drawable.devreply_ic_warning), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(Brand.error))
                    BasicText(item.failure, style = text(12.sp, FontWeight.Bold, Brand.error).copy(textAlign = TextAlign.End))
                }
            } else {
                Kicker(if (item.attachments.isEmpty()) "Sending…" else "Uploading…")
            }
        }
    }
}

@Composable
private fun TextBubble(text: String, fromUser: Boolean, muted: Boolean = false) {
    val theme = DevReply.theme
    val shape = RoundedCornerShape(14.dp)
    val bubble = if (fromUser) {
        // Like the blue bubble on devreply.com: flat colour, rounded.
        Modifier.background(theme.userBubble, shape)
    } else {
        Modifier.brutal(shadow = 3.dp, lineWidth = 2.5.dp, cornerRadius = 14.dp)
    }
    SelectionContainer {
        BasicText(
            text,
            bubble.padding(horizontal = 14.dp, vertical = 11.dp),
            style = text(17.sp, FontWeight.Medium, if (fromUser) theme.userBubbleText else if (muted) Brand.muted else theme.ink),
        )
    }
}

/** A file in a message: document icon, name, size. */
@Composable
private fun FileChip(name: String, size: Int?) {
    val context = LocalContext.current
    Row(
        Modifier.widthIn(max = 240.dp).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp, 44.dp).background(Brand.lemon).border(2.dp, Brand.ink), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.devreply_ic_file), null, Modifier.size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(name, style = text(15.sp, FontWeight.Bold, Brand.ink), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (size != null) {
                BasicText(Formatter.formatShortFileSize(context, size.toLong()), style = text(12.sp, FontWeight.Medium, Brand.muted))
            }
        }
    }
}

@Composable
private fun RemoteImage(url: String, width: Int?, height: Int?, onClick: () -> Unit) {
    val ratio = (width ?: 4).toFloat() / maxOf(height ?: 3, 1)
    val image by produceState(ImageCache.cached(url), url) { value = ImageCache.image(url) }
    Box(
        Modifier
            .sizeIn(maxWidth = 220.dp, maxHeight = 260.dp)
            .aspectRatio(ratio)
            .drawBehind { drawRect(Brand.ink, topLeft = Offset(4.dp.toPx(), 4.dp.toPx()), size = size) }
            .background(Brand.grey)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Photo" },
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image
        if (loaded != null) {
            Image(loaded, null, Modifier.fillMaxSize().clipToBounds(), contentScale = ContentScale.Crop)
        } else {
            CircularProgressIndicator(Modifier.size(24.dp), color = Brand.ink, strokeWidth = 3.dp)
        }
        Box(Modifier.matchParentSize().border(3.dp, Brand.ink))
    }
}

@Composable
private fun ImageViewer(url: String, close: () -> Unit) {
    val image by produceState<ImageBitmap?>(ImageCache.cached(url), url) { value = ImageCache.image(url) }
    var zoom by remember { mutableFloatStateOf(1f) }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Brand.ink)) {
            val loaded = image
            if (loaded != null) {
                Image(
                    loaded, "Photo",
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTransformGestures { _, _, z, _ -> zoom = (zoom * z).coerceIn(1f, 5f) } }
                        .graphicsLayer(scaleX = zoom, scaleY = zoom),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            IconSquareButton(
                R.drawable.devreply_ic_close, "Close", close,
                Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(20.dp), fill = Brand.lemon,
            )
        }
    }
}
