package com.devreply.sdk.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devreply.sdk.Block

/**
 * A question from the team with answers as buttons (spec 05, 0.5.0): the question in the team bubble (Markdown),
 * the options under it in the Loud look, as wide as the widest. Before an answer every option is live; after
 * one ([chosen]) that option stays highlighted in the theme's primary colour and the others go quiet.
 */
@Composable
internal fun ButtonsQuestion(block: Block.Buttons, chosen: String?, onAnswer: (Block.Buttons.Option) -> Unit) {
    if (block.nodes.isNotEmpty()) MarkdownBubble(block.nodes)
    // The end padding leaves room for the hard shadow.
    Column(
        Modifier.width(IntrinsicSize.Max).padding(top = 2.dp, end = 3.dp, bottom = 3.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        block.options.forEach { option -> OptionButton(option, chosen, onAnswer) }
    }
}

@Composable
private fun OptionButton(option: Block.Buttons.Option, chosen: String?, onAnswer: (Block.Buttons.Option) -> Unit) {
    val theme = LocalTheme.current
    val isChosen = option.id == chosen
    val quiet = chosen != null && !isChosen
    BrutalButton(
        onClick = { onAnswer(option) },
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (quiet) 0.4f else 1f)
            .testTag("devreply.option.${option.id}")
            .semantics { selected = isChosen },
        fill = if (isChosen) theme.primary else theme.surface,
        shadow = if (quiet) 0.dp else 3.dp,
        enabled = chosen == null,
    ) {
        BasicText(
            option.label,
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp).heightIn(min = 22.dp),
            style = text(16.sp, FontWeight.Bold, if (isChosen) theme.onHeaderColor else theme.ink),
        )
    }
}
