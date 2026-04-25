package com.github.catatafishen.agentbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.jetbrains.jewel.bridge.retrieveColor
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text

@Composable
fun ChatInputPanel(
    modifier: Modifier = Modifier,
    /** Vertical side-button rail (left column). */
    sideButtons: @Composable BoxScope.() -> Unit = {},
    /** Main text editor area (top of the right column). */
    editor: @Composable BoxScope.() -> Unit = {},
    /** Shortcut hint strip — [RowScope] allows using `Modifier.weight(1f)`. */
    shortcuts: @Composable RowScope.() -> Unit = {},
    /** Model selector toolbar placed to the right of the shortcut strip. */
    modelSelector: @Composable RowScope.() -> Unit = {},
    /** Send / Stop action button (rightmost in the bottom bar). */
    sendButton: @Composable RowScope.() -> Unit = {},
) {
    val isDark = JewelTheme.isDark
    val fillColor = retrieveColor(
        key = "TextField.background",
        isDark = isDark,
        default = Color(0xFFFFFFFF),
        defaultDark = Color(0xFF2B2D30),
    )
    val dividerColor = retrieveColor(
        key = "Separator.separatorColor",
        isDark = isDark,
        default = Color(0xFFCDCDCD),
        defaultDark = Color(0xFF4E5157),
    )
    val borderColor = retrieveColor(
        key = "Component.borderColor",
        isDark = isDark,
        default = Color(0xFFADADAD),
        defaultDark = Color(0xFF5A5D63),
    )

    Row(
        modifier = modifier.drawBehind {
            val arc = CornerRadius(8.dp.toPx())
            drawRoundRect(fillColor, cornerRadius = arc)
            drawRoundRect(color = borderColor, cornerRadius = arc, style = Stroke(1.dp.toPx()))
        },
    ) {
        Box(content = sideButtons)

        // 1 dp vertical divider, inset 2 dp top and bottom
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 2.dp)
                .width(1.dp)
                .background(dividerColor),
        )

        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                content = editor,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                shortcuts()
                modelSelector()
                sendButton()
            }
        }
    }
}

// ── Preview ───────────────────────────────────────────────────────────────────

@Preview
@Composable
private fun ChatInputPanelPreview() {
    ChatInputPanel(
        modifier = Modifier.fillMaxWidth().height(120.dp),
        sideButtons = {
            Column(
                modifier = Modifier.width(40.dp).fillMaxHeight().padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PreviewIconBox("↺")
                PreviewIconBox("@")
                PreviewIconBox("◉")
            }
        },
        editor = {
            Box(modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
                Text("Type a message...", color = Color.Gray, fontSize = 13.sp)
            }
        },
        shortcuts = {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KeyToken("↵")
                Text("Send", fontSize = 11.sp, color = Color.Gray)
                KeyToken("⇧")
                Text("+", fontSize = 11.sp, color = Color.Gray)
                KeyToken("↵")
                Text("New line", fontSize = 11.sp, color = Color.Gray)
            }
        },
        modelSelector = {
            Box(
                modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("GPT-4.1 ▾", fontSize = 11.sp, color = Color.Gray)
            }
        },
        sendButton = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(0xFF0088FF), RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("▶", color = Color.White, fontSize = 12.sp)
            }
        },
    )
}

@Composable
private fun PreviewIconBox(label: String) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color.Gray.copy(alpha = 0.2f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp)
    }
}
