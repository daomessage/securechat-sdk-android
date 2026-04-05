package space.securechat.sample.ui.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun SwipeToBackWrapper(
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    var offsetX by remember { mutableStateOf(0f) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { },
                    onHorizontalDrag = { change, dragAmount ->
                        // Only allow dragging from the left edge (e.g. x < 100) or if we are already swiping right
                        if (change.position.x < 100.dp.toPx() || offsetX > 0) {
                            change.consume()
                            offsetX = (offsetX + dragAmount).coerceAtLeast(0f)
                        }
                    },
                    onDragEnd = {
                        if (offsetX > screenWidth.toPx() * 0.3f) {
                            onBack()
                        }
                        offsetX = 0f
                    },
                    onDragCancel = { offsetX = 0f }
                )
            }
    ) {
        // We could apply graphicsLayer { translationX = offsetX } here for a visual drag, 
        // but for simplicity and stability, we just use it as a trigger wrapper.
        content()
    }
}
