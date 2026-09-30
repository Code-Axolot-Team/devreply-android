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
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
import com.devreply.sdk.PushManager
import com.devreply.sdk.R
import com.devreply.sdk.replyAllowText
import com.devreply.sdk.replyTimeText
import com.devreply.sdk.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
    /** [by]: the persona label above it, on the first team message of each group (spec 05, 0.4.0). */
    data class Msg(val message: Message, val by: com.devreply.sdk.Persona? = null) : ChatItem { override val key get() = message.id.toString() }
    data class Pend(val item: ConversationModel.Pending) : ChatItem { override val key get() = "pending-${item.id}" }

    /** Under the user's first message: we got it, please allow up to <reply time>. */
    data object Notice : ChatItem { override val key get() = "notice" }
}

@Composable
internal fun ConversationScreen(conversationId: UUID?, category: DevReplyCategory?, back: () -> Unit) {
    val model = remember { ConversationModel(conversationId, category) }
    val theme = LocalTheme.current
    val config = Messenger.config
    val haptics = LocalHapticFeedback.current
    var viewing by remember { mutableStateOf<String?>(null) }
    // Started on this screen (not opened from the list): the email ask belongs to its first message only.
    val startedHere = remember { conversationId == null }
    var emailAskDone by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val hideKeyboardAfter = with(LocalDensity.current) { 90.dp.toPx() }

    // Restarts (loads at once) when a login gave this device its earlier user's conversations back.
    LaunchedEffect(model.conversationId, Messenger.reloads) {
        Messenger.loadProfileIfNeeded()
        while (true) {
            model.load()
            delay(3_000)
            // While the live channel is up, messages come over it: the poll waits until it's down.
            snapshotFlow { com.devreply.sdk.LiveUpdates.isLive }.first { !it }
        }
    }
    // Messages from the live channel, into this conversation.
    DisposableEffect(model) {
        val listener: (UUID, com.devreply.sdk.Message) -> Unit = model::receive
        Messenger.liveListeners += listener
        onDispose { Messenger.liveListeners -= listener }
    }
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(model.conversationId) {
        Messenger.visibleConversation = model.conversationId
        // On screen now: its notification has done its job.
        model.conversationId?.let { PushManager.clear(appContext, it) }
        onDispose { Messenger.visibleConversation = null }
    }
    LaunchedEffect(model.sentCount) {
        if (model.sentCount > 0) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    val firstFromUser = model.messages.indexOfFirst { it.isFromUser }
    val items = buildList {
        // A group of team replies ends after the user's message, a time label, or another persona.
        var groupPersona: String? = null
        model.messages.forEachIndexed { i, m ->
            val gap = i == 0 || Duration.between(model.messages[i - 1].createdAt, m.createdAt).toMinutes() > 15
            if (gap) {
                add(ChatItem.Time(m.createdAt, "time-${m.id}"))
                groupPersona = null
            }
            var by: com.devreply.sdk.Persona? = null
            when {
                m.isFromUser -> groupPersona = null
                m.author == Message.Author.System -> Unit
                else -> {
                    val persona = m.persona
                    if (persona != null && persona.name != groupPersona) by = persona
                    groupPersona = persona?.name
                }
            }
            add(ChatItem.Msg(m, by))
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
                                is ChatItem.Msg -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item.by?.let { PersonaLabel(it) }
                                    MessageRow(
                                        item.message, config.teamName,
                                        chosen = if (item.message.blocks.any { it is Block.Buttons }) model.chosenOption(item.message.id) else null,
                                        onAnswer = { model.answer(item.message, it) },
                                        onOpenImage = { viewing = it },
                                    )
                                }
                                is ChatItem.Pend -> PendingRow(item.item, config.teamName) { model.retry(item.item) }
                                ChatItem.Notice -> ReceivedNotice(config.teamName, config.replyAllowText, Messenger.profile?.email?.takeIf { it.isNotBlank() })
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
                // One card at a time: the email ask first, then notifications.
                if (showsEmailAsk) {
                    EmailAskCard { emailAskDone = true }
                } else if (model.messages.any { it.isFromUser }) {
                    PushManager.askState(LocalContext.current)?.let { PushAskCard(it, config.teamName) }
                }
                // A new conversation starts with what DevReply.present passed (not sent until the user sends it).
                Composer(
                    autoFocus = model.conversationId == null,
                    prefill = if (model.conversationId == null) Messenger.presentMessage.orEmpty() else "",
                ) { text, staged -> model.send(text, staged) }
            }
        }
    }

    viewing?.let { url -> ImageViewer(url) { viewing = null } }
}

@Composable
private fun TopBar(back: () -> Unit) {
    val theme = LocalTheme.current
    val config = Messenger.config
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.headerFill)
            .drawBehind {
                val h = theme.stroke(3.dp).toPx()
                drawRect(theme.line, topLeft = Offset(0f, size.height - h), size = size.copy(height = h))
            }
            .statusBarsPadding()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconSquareButton(R.drawable.devreply_ic_back, com.devreply.sdk.t("back"), back, Modifier.testTag("devreply.back"))
        TeamAvatar(config.teamName, 32.dp, imageUrl = config.appIconUrl, modifier = Modifier.testTag("devreply.appicon"))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            BasicText(config.teamName.ifEmpty { com.devreply.sdk.t("chat") }, style = display(16.sp, theme.onHeaderColor), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (config.replyTimeText.isNotEmpty()) {
                BasicText(config.replyTimeText, style = text(11.sp, FontWeight.Medium, theme.onHeaderColor.copy(alpha = 0.75f)), maxLines = 1)
            }
        }
    }
}

/** Shown while the conversation is empty, at the top of the screen. */
@Composable
private fun Intro(category: DevReplyCategory) {
    val theme = LocalTheme.current
    val title = Messenger.config.startButtons.firstOrNull { it.category == category }?.let { Messenger.config.title(it) } ?: category.defaultTitle
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 36.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.brutal(theme, shadow = 6.dp).padding(18.dp)) {
            Image(categoryArt(category), null, Modifier.size(64.dp))
        }
        BasicText(title, style = display(26.sp).copy(textAlign = TextAlign.Center))
        BasicText(category.prompt, style = text(16.sp, FontWeight.Medium, theme.muted).copy(textAlign = TextAlign.Center))
    }
}

// MARK: Composer

@Composable
private fun Composer(autoFocus: Boolean, prefill: String = "", send: (String, List<Staged>) -> Unit) {
    val theme = LocalTheme.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The cursor after a prefilled message, ready to add to it.
    var draft by remember { mutableStateOf(TextFieldValue(prefill, TextRange(prefill.length))) }
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
                        is Staged.Problem.TooBig -> com.devreply.sdk.t("too_big", "name" to p.name)
                        else -> com.devreply.sdk.t("unreadable_file")
                    }
                }
            }
            if (staged.size > 4) {
                staged = staged.take(4)
                pickError = com.devreply.sdk.t("too_many")
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

    val canSend = draft.text.isNotBlank() || staged.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.surface)
            .drawBehind { drawRect(theme.line, size = size.copy(height = theme.stroke(3.dp).toPx())) }
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
        pickError?.let { BasicText(it, style = text(13.sp, FontWeight.Bold, theme.error)) }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                IconSquareButton(
                    R.drawable.devreply_ic_attach, com.devreply.sdk.t("attach"), { menu = true },
                    Modifier.testTag("devreply.attach"), size = 46.dp,
                )
                // The Loud look, not Material's: a square surface card with the outline, in the theme's colours.
                CompositionLocalProvider(LocalContentColor provides theme.ink) {
                DropdownMenu(
                    menu, { menu = false },
                    shape = RectangleShape,
                    containerColor = theme.surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = BorderStroke(theme.stroke(2.5.dp), theme.line),
                ) {
                    DropdownMenuItem(
                        text = { BasicText(com.devreply.sdk.t("photo"), style = text(16.sp, FontWeight.Medium)) },
                        leadingIcon = { Image(painterResource(R.drawable.devreply_ic_photo), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(theme.ink)) },
                        onClick = {
                            menu = false
                            photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    )
                    DropdownMenuItem(
                        text = { BasicText(com.devreply.sdk.t("file"), style = text(16.sp, FontWeight.Medium)) },
                        leadingIcon = { Image(painterResource(R.drawable.devreply_ic_file), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(theme.ink)) },
                        onClick = {
                            menu = false
                            files.launch(arrayOf("*/*"))
                        },
                    )
                }
                }
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
                    .background(theme.surface)
                    .border(theme.stroke(3.dp), theme.line)
                    .focusRequester(focus)
                    .testTag("devreply.composer"),
                textStyle = text(17.sp),
                cursorBrush = SolidColor(theme.ink),
                maxLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), contentAlignment = Alignment.CenterStart) {
                        if (draft.text.isEmpty()) BasicText(com.devreply.sdk.t("composer.placeholder"), style = text(17.sp, color = theme.muted.copy(alpha = 0.7f)))
                        inner()
                    }
                },
            )
            BrutalButton(
                onClick = {
                    send(draft.text, staged)
                    draft = TextFieldValue("")
                    staged = emptyList()
                    pickError = null
                },
                modifier = Modifier.alpha(if (canSend) 1f else 0.45f).testTag("devreply.send"),
                fill = theme.accent,
                shadow = 3.dp,
                enabled = canSend,
                label = com.devreply.sdk.t("send"),
            ) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.devreply_ic_send), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(theme.onAccentColor))
                }
            }
        }
    }
}

@Composable
private fun StagedThumb(item: Staged, remove: () -> Unit) {
    val theme = LocalTheme.current
    Box {
        Box(Modifier.size(64.dp).border(theme.stroke(2.5.dp), theme.line).clipToBounds()) {
            if (item.preview != null) {
                Image(item.preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(
                    Modifier.fillMaxSize().background(theme.brand).padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Image(painterResource(R.drawable.devreply_ic_file), null, Modifier.size(18.dp), colorFilter = ColorFilter.tint(theme.onBrand))
                    BasicText(item.name, style = text(10.sp, FontWeight.Bold, theme.onBrand).copy(textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.matchParentSize().border(theme.stroke(2.5.dp), theme.line))
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(7.dp, (-7).dp)
                .size(22.dp)
                .background(theme.accent)
                .border(theme.stroke(2.dp), theme.line)
                .clickable(onClick = remove)
                .semantics { contentDescription = com.devreply.sdk.t("remove_attachment", "name" to item.name) },
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.devreply_ic_close), null, Modifier.size(12.dp), colorFilter = ColorFilter.tint(theme.onAccentColor))
        }
    }
}

// MARK: Name first (spec 05)

/** Asked once, before the first message, unless the host app passed a name with `DevReply.setUser`. */
@Composable
private fun NameForm() {
    val theme = LocalTheme.current
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
                error = com.devreply.sdk.t("error.save")
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
            .background(theme.cardFill)
            .drawBehind { drawRect(theme.line, size = size.copy(height = theme.stroke(3.dp).toPx())) }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Kicker(com.devreply.sdk.t("name.kicker"), inverted = true)
        BasicText(
            com.devreply.sdk.t("name.text"),
            style = text(15.sp, FontWeight.Medium, theme.onCardColor),
        )
        FormField(
            name, { name = it }, com.devreply.sdk.t("name.placeholder"),
            Modifier.focusRequester(nameFocus).testTag("devreply.profile.name"),
            KeyboardOptions(capitalization = KeyboardCapitalization.Words, keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
            KeyboardActions(onNext = { emailFocus.requestFocus() }),
        )
        FormField(
            email, { email = it }, com.devreply.sdk.t("email.optional"),
            Modifier.focusRequester(emailFocus).testTag("devreply.profile.email"),
            KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            KeyboardActions(onDone = { save() }),
        )
        error?.let { BasicText(it, style = text(13.sp, FontWeight.Bold, theme.errorOnCard)) }
        BrutalButton(
            ::save,
            Modifier.fillMaxWidth().alpha(if (name.isBlank()) 0.5f else 1f).testTag("devreply.profile.save"),
            fill = theme.accent,
            shadow = 4.dp,
            enabled = !saving && name.isNotBlank(),
        ) {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                BasicText(if (saving) com.devreply.sdk.t("saving") else com.devreply.sdk.t("name.start"), style = text(16.sp, FontWeight.Bold, theme.onAccentColor))
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
    // Used on the prompt cards: a surface field with the outline colour.
    val theme = LocalTheme.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth().height(46.dp).background(theme.surface).border(theme.stroke(2.5.dp), theme.line),
        textStyle = text(17.sp, color = theme.ink),
        cursorBrush = SolidColor(theme.ink),
        singleLine = true,
        keyboardOptions = keyboard,
        keyboardActions = actions,
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) BasicText(placeholder, style = text(17.sp, color = theme.muted.copy(alpha = 0.7f)))
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
private fun ReceivedNotice(teamName: String, allow: String, email: String?) {
    val theme = LocalTheme.current
    val body = "$allow " + (email?.let { com.devreply.sdk.t("notice.email", "email" to it) } ?: com.devreply.sdk.t("notice.here"))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, end = 4.dp)
            .brutal(theme, fill = theme.noticeFill, shadow = 3.dp)
            .padding(12.dp)
            .semantics(mergeDescendants = true) {}
            .testTag("devreply.notice"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TeamAvatar(teamName, 36.dp, imageUrl = Messenger.config.appIconUrl)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(com.devreply.sdk.t("notice.title"), style = text(15.sp, FontWeight.Bold))
            BasicText(body, style = text(14.sp, FontWeight.Medium))
        }
    }
}

/** Right after the first message of a request, if we don't have their email: optional, one tap to skip. */
@Composable
private fun EmailAskCard(done: () -> Unit) {
    val theme = LocalTheme.current
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
                error = com.devreply.sdk.t("error.save")
            }
            saving = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(theme.cardFill)
            .drawBehind { drawRect(theme.line, size = size.copy(height = theme.stroke(3.dp).toPx())) }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(com.devreply.sdk.t("email_ask.title"), Modifier.weight(1f), style = text(15.sp, FontWeight.Bold, theme.onCardColor))
            BasicText(
                com.devreply.sdk.t("no_thanks"),
                Modifier.clickable(onClick = done).padding(4.dp).testTag("devreply.emailask.skip"),
                style = text(14.sp, FontWeight.Bold, theme.mutedOnCard),
            )
        }
        BasicText(
            com.devreply.sdk.t("email_ask.text"),
            style = text(13.sp, FontWeight.Medium, theme.onCardColor),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                FormField(
                    email, { email = it }, com.devreply.sdk.t("email_ask.placeholder"),
                    Modifier.testTag("devreply.emailask.field"),
                    KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    KeyboardActions(onDone = { save() }),
                )
            }
            BrutalButton(
                ::save,
                Modifier.alpha(if (email.isBlank()) 0.5f else 1f).testTag("devreply.emailask.save"),
                fill = theme.accent,
                shadow = 3.dp,
                enabled = !saving && email.isNotBlank(),
                label = com.devreply.sdk.t("save"),
            ) {
                Box(Modifier.height(46.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    BasicText(if (saving) "…" else com.devreply.sdk.t("save"), style = text(15.sp, FontWeight.Bold, theme.onAccentColor))
                }
            }
        }
        error?.let { BasicText(it, style = text(13.sp, FontWeight.Bold, theme.errorOnCard)) }
    }
}

/**
 * After the user's first message, when the app forwards pushes but notifications are off: "Turn on"
 * (Android 13+ asks for the permission), or "Open Settings" once they said no. "Not now" hides it for 3 days.
 */
@Composable
private fun PushAskCard(state: PushManager.AskState, teamName: String) {
    val context = LocalContext.current
    val who = teamName.ifEmpty { com.devreply.sdk.t("team") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PushManager.asked(context)
    }
    val first = state == PushManager.AskState.FirstAsk
    val theme = LocalTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(theme.cardFill)
            .drawBehind { drawRect(theme.line, size = size.copy(height = theme.stroke(3.dp).toPx())) }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(40.dp).background(theme.surface).border(theme.stroke(2.dp), theme.line),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.devreply_ic_bell), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(theme.ink))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicText(
                com.devreply.sdk.t(if (first) "push.title" else "push.off_title"),
                style = text(15.sp, FontWeight.Bold, theme.onCardColor),
            )
            BasicText(
                com.devreply.sdk.t(if (first) "push.text" else "push.off_text", "team" to who),
                style = text(14.sp, FontWeight.Medium, theme.onCardColor),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val label = com.devreply.sdk.t(if (first) "push.turn_on" else "push.open_settings")
                BrutalButton(
                    {
                        if (first && android.os.Build.VERSION.SDK_INT >= 33) {
                            permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            (context as? android.app.Activity)?.let(PushManager::openSettings)
                        }
                    },
                    Modifier.testTag("devreply.push.enable"),
                    fill = theme.accent,
                    shadow = 3.dp,
                    label = label,
                ) {
                    Box(Modifier.height(40.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        BasicText(label, style = text(15.sp, FontWeight.Bold, theme.onAccentColor))
                    }
                }
                BasicText(
                    com.devreply.sdk.t("push.not_now"),
                    Modifier.clickable { PushManager.notNow(context) }.padding(4.dp).testTag("devreply.push.notnow"),
                    style = text(14.sp, FontWeight.Bold, theme.mutedOnCard),
                )
            }
        }
    }
}

// MARK: Messages

/** "MON · 18:52", like the timestamps on devreply.com. */
@Composable
private fun TimeLabel(at: Instant) {
    val context = LocalContext.current
    val locale = com.devreply.sdk.L10n.javaLocale
    val zoned = at.atZone(ZoneId.systemDefault())
    val day = DateTimeFormatter.ofPattern("EEE", locale).format(zoned)
    // The device's own 12/24-hour setting, unless the app picked another language.
    val time = if (com.devreply.sdk.L10n.override == null) {
        DateFormat.getTimeFormat(context).format(Date.from(at))
    } else {
        DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT).withLocale(locale).format(zoned)
    }
    Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
        Kicker("$day · $time")
    }
}

/** "[face] Anna · Support" above a group of replies from one persona. */
@Composable
private fun PersonaLabel(persona: com.devreply.sdk.Persona) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).testTag("devreply.persona"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TeamAvatar(persona.name, 22.dp, imageUrl = persona.avatarUrl, lineWidth = 2.dp)
        BasicText(persona.name, style = text(13.sp, FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (persona.title.isNotEmpty()) {
            BasicText(persona.title, style = text(13.sp, FontWeight.Medium, LocalTheme.current.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Lays out one message: the user's on the right, the team's on the left (with the app's avatar when no persona is shown). */
@Composable
private fun ChatRow(fromUser: Boolean, teamName: String, showAvatar: Boolean = true, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = if (fromUser) 48.dp else 0.dp, end = if (fromUser) 4.dp else 48.dp),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!fromUser && showAvatar) {
            TeamAvatar(
                teamName, 30.dp, fill = LocalTheme.current.brand, imageUrl = Messenger.config.appIconUrl,
                initialsColor = LocalTheme.current.onBrand,
            )
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
internal fun MessageRow(
    message: Message,
    teamName: String,
    /** A buttons question: the option the user picked, null while unanswered. */
    chosen: String? = null,
    onAnswer: (Block.Buttons.Option) -> Unit = {},
    onOpenImage: (String) -> Unit,
) {
    val context = LocalContext.current
    if (message.author == Message.Author.System) {
        // e.g. "✓ Marked as resolved…": a quiet line, not a bubble.
        // The server's own lines ("✓ Marked as resolved…") in the user's language.
        val resolved = message.blocks.any { it is Block.Text && (it.key == "resolved" || it.text == com.devreply.sdk.L10n.LEGACY_RESOLVED) }
        BasicText(
            if (resolved) com.devreply.sdk.t("system.resolved") else message.plainText,
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            style = text(13.sp, FontWeight.Bold, LocalTheme.current.muted).copy(textAlign = TextAlign.Center),
        )
        return
    }
    ChatRow(message.isFromUser, teamName, showAvatar = message.persona == null) {
        message.blocks.forEach { block ->
            when (block) {
                is Block.Text -> TextBubble(block.text, message.isFromUser)
                is Block.Image -> RemoteImage(block.url, block.width, block.height) { onOpenImage(block.url) }
                is Block.File -> BrutalButton(
                    { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(block.url))) } },
                    shadow = 3.dp,
                    label = com.devreply.sdk.t("file_named", "name" to block.name),
                ) { FileChip(block.name, block.size) }
                is Block.Unsupported -> TextBubble(block.fallback, message.isFromUser, muted = true)
                // Formatting is for the team's replies; anything else shows its plain text.
                is Block.Markdown ->
                    if (message.isFromUser || block.nodes.isEmpty()) TextBubble(block.fallback, message.isFromUser)
                    else MarkdownBubble(block.nodes)
                is Block.Buttons ->
                    if (message.isFromUser) TextBubble(block.fallback, fromUser = true)
                    else ButtonsQuestion(block, chosen, onAnswer)
            }
        }
    }
}

@Composable
private fun PendingRow(item: ConversationModel.Pending, teamName: String, retry: () -> Unit) {
    val theme = LocalTheme.current
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
                            .border(theme.stroke(3.dp), theme.line)
                            .alpha(0.7f),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.brutal(theme, shadow = 3.dp).alpha(0.7f)) { FileChip(staged.name, staged.attachment.data.size) }
                }
            }
            if (item.text.isNotEmpty()) {
                Box(Modifier.alpha(if (item.failure == null) 0.7f else 1f)) { TextBubble(item.text, fromUser = true) }
            }
            if (item.failure != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Image(painterResource(R.drawable.devreply_ic_warning), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(theme.error))
                    BasicText(item.failure, style = text(12.sp, FontWeight.Bold, theme.error).copy(textAlign = TextAlign.End))
                }
            } else {
                Kicker(if (item.attachments.isEmpty()) com.devreply.sdk.t("sending") else com.devreply.sdk.t("uploading"))
            }
        }
    }
}

@Composable
private fun TextBubble(text: String, fromUser: Boolean, muted: Boolean = false) {
    val theme = LocalTheme.current
    val shape = RoundedCornerShape(14.dp)
    val bubble = if (fromUser) {
        // Like the blue bubble on devreply.com: flat colour, rounded.
        Modifier.background(theme.userBubble, shape)
    } else {
        Modifier.teamBubble(theme)
    }
    SelectionContainer {
        BasicText(
            text,
            bubble.padding(horizontal = 14.dp, vertical = 11.dp),
            style = text(17.sp, FontWeight.Medium, if (fromUser) theme.userBubbleText else if (muted) theme.muted else theme.teamBubbleTextColor),
        )
    }
}

/** A file in a message: document icon, name, size. */
@Composable
private fun FileChip(name: String, size: Int?) {
    val context = LocalContext.current
    val theme = LocalTheme.current
    Row(
        Modifier.widthIn(max = 240.dp).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp, 44.dp).background(theme.brand).border(theme.stroke(2.dp), theme.line), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.devreply_ic_file), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(theme.onBrand))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(name, style = text(15.sp, FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (size != null) {
                BasicText(Formatter.formatShortFileSize(context, size.toLong()), style = text(12.sp, FontWeight.Medium, theme.muted))
            }
        }
    }
}

@Composable
private fun RemoteImage(url: String, width: Int?, height: Int?, onClick: () -> Unit) {
    val theme = LocalTheme.current
    val ratio = (width ?: 4).toFloat() / maxOf(height ?: 3, 1)
    val image by produceState(ImageCache.cached(url), url) { value = ImageCache.image(url) }
    Box(
        Modifier
            .sizeIn(maxWidth = 220.dp, maxHeight = 260.dp)
            .aspectRatio(ratio)
            .drawBehind { drawRect(theme.shadowColor, topLeft = Offset(4.dp.toPx(), 4.dp.toPx()), size = size) }
            .background(theme.subtle)
            .clickable(onClick = onClick)
            .semantics { contentDescription = com.devreply.sdk.t("open_photo") },
        contentAlignment = Alignment.Center,
    ) {
        val loaded = image
        if (loaded != null) {
            Image(loaded, null, Modifier.fillMaxSize().clipToBounds(), contentScale = ContentScale.Crop)
        } else {
            CircularProgressIndicator(Modifier.size(24.dp), color = theme.ink, strokeWidth = 3.dp)
        }
        Box(Modifier.matchParentSize().border(theme.stroke(3.dp), theme.line))
    }
}

@Composable
private fun ImageViewer(url: String, close: () -> Unit) {
    val image by produceState<ImageBitmap?>(ImageCache.cached(url), url) { value = ImageCache.image(url) }
    var zoom by remember { mutableFloatStateOf(1f) }
    // Photos on the theme's darkest colour (the outline in both presets: near-black), the spinner in its lightest.
    val theme = LocalTheme.current
    val colours = listOf(theme.line, theme.ink, theme.background, theme.headerFill).sortedBy { it.luminance() }
    val backdrop = colours.first()
    val onBackdrop = colours.last()
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // The dialog is its own window: its status bar icons follow the backdrop, not the app's theme.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                // Edge to edge: the backdrop under the status bar too, not the dimmed app.
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    window.attributes = window.attributes.apply {
                        fitInsetsTypes = 0
                        // Into the camera cutout's band as well (the status bar).
                        layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    }
                }
                window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = backdrop.isLight
                    isAppearanceLightNavigationBars = backdrop.isLight
                }
            }
        }
        Box(Modifier.fillMaxSize().background(backdrop)) {
            val loaded = image
            if (loaded != null) {
                Image(
                    loaded, com.devreply.sdk.t("photo"),
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTransformGestures { _, _, z, _ -> zoom = (zoom * z).coerceIn(1f, 5f) } }
                        .graphicsLayer(scaleX = zoom, scaleY = zoom),
                    contentScale = ContentScale.Fit,
                )
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = onBackdrop)
            }
            IconSquareButton(
                R.drawable.devreply_ic_close, com.devreply.sdk.t("close_photo"), close,
                Modifier.align(Alignment.TopEnd).systemBarsPadding().padding(20.dp), fill = theme.brand, tint = theme.onBrand,
            )
        }
    }
}
