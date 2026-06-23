package org.dpdns.sylw.videostreamer.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 安全的按钮组件，支持三种状态：
 * - **IDLE（空闲）**：可点击，显示 [content](IDLE)，背景色 [inactiveContainerColor]
 * - **PENDING（连接中）**：不可点击，显示 [content](PENDING)，背景色 [pendingContainerColor]
 * - **ACTIVE（活动中）**：可点击（点击即停止），显示 [content](ACTIVE)，背景色 [activeContainerColor]
 *
 * 当 [isPending] = true 时按钮始终不可点击，即使 [enabled] = false 时也一样。
 * [enabled] 仅控制 IDLE 和 ACTIVE 状态下的可点击性。
 */
enum class SafeButtonState { IDLE, PENDING, ACTIVE }

@Composable
fun SafeButton(
    isPending: Boolean,
    isActive: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeContainerColor: Color,
    inactiveContainerColor: Color = MaterialTheme.colorScheme.primary,
    pendingContainerColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable (SafeButtonState) -> Unit,
) {
    val state = when {
        isPending -> SafeButtonState.PENDING
        isActive -> SafeButtonState.ACTIVE
        else -> SafeButtonState.IDLE
    }

    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = when {
            isPending -> false
            !enabled -> false
            else -> true
        },
        contentPadding = contentPadding,
        colors = when (state) {
            SafeButtonState.IDLE -> ButtonDefaults.buttonColors(
                containerColor = inactiveContainerColor
            )
            SafeButtonState.ACTIVE -> ButtonDefaults.buttonColors(
                containerColor = activeContainerColor
            )
            SafeButtonState.PENDING -> ButtonDefaults.buttonColors(
                disabledContainerColor = pendingContainerColor
            )
        },
        content = { content(state) }
    )
}
