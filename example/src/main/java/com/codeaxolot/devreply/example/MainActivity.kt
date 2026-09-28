package com.codeaxolot.devreply.example

import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.devreply.sdk.DevReply
import kotlinx.coroutines.launch

private val ink = Color(0xFF111111)
private val lemon = Color(0xFFF6EB37)
private val pink = Color(0xFFFF5FA2)

/** A blank app with one button. It opens DevReply's native chat. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { Home(open = { DevReply.present(this) }) }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { DevReply.refresh() }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Home(open: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(lemon)
            .semantics { testTagsAsResourceId = true }
            .systemBarsPadding()
            .padding(24.dp)
            .padding(top = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BasicText(
            "DEMO APP",
            Modifier.background(ink).padding(horizontal = 8.dp, vertical = 4.dp),
            style = TextStyle(color = lemon, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
        )
        BasicText(
            "Talk to the\ndeveloper.",
            style = TextStyle(color = ink, fontSize = 44.sp, fontWeight = FontWeight.Black, lineHeight = 48.sp),
        )
        BasicText(
            "This is a blank app with one button. It opens DevReply's native chat.",
            style = TextStyle(color = ink, fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp),
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(bottom = 24.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(62.dp)
                    .drawBehind { drawRect(ink, topLeft = Offset(6.dp.toPx(), 6.dp.toPx()), size = size) }
                    .background(pink)
                    .border(3.dp, ink)
                    .clickable(onClick = open)
                    .testTag("openMessenger")
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "Message the developer",
                    Modifier.weight(1f),
                    style = TextStyle(color = ink, fontSize = 19.sp, fontWeight = FontWeight.Bold),
                )
                Image(painterResource(R.drawable.arrow_right), null, Modifier.size(24.dp))
            }
            // Unread replies from the team: DevReply.unreadCount is Compose state, so this updates on its own.
            val unread = DevReply.unreadCount
            if (unread > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(10.dp, (-12).dp)
                        .sizeIn(minWidth = 28.dp, minHeight = 28.dp)
                        .background(lemon)
                        .border(2.5.dp, ink),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText("$unread", Modifier.padding(horizontal = 4.dp), style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Black))
                }
            }
        }
    }
}
