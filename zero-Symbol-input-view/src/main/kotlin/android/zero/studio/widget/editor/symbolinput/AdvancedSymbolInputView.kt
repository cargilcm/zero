package android.zero.studio.widget.editor.symbolinput

import android.content.Context
import android.util.AttributeSet
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
// Import the appropriate CodeEditor class in your project, e.g.:
import io.github.rosemoe.sora.widget.CodeEditor

class AdvancedSymbolInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AbstractComposeView(context, attrs, defStyleAttr) {

    // Change type from Any? to CodeEditor?
    var editor: CodeEditor? = null
        private set

    fun bindEditor(editor: Any?) {
        this.editor = editor as? CodeEditor
    }

    @Composable
    override fun Content() {
        MaterialTheme(colorScheme = darkColorScheme(surface = Color(0xFFF9F6F0))) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFAF7F2)
            ) {
                AdvancedSymbolInputToolbar(
                    groups = groupsState,
                    uiSettings = uiSettingsState,
                    onSymbolClicked = { item, isLong ->
                        val action = if (isLong) item.longAction else item.shortAction
                        val text = if (isLong) item.longText else item.shortText

                        editor?.let { ed ->
                            SymbolActionExecutor.execute(ed, action ?: 0, text, onOpenManagerListener)
                        }
                    }
                )
            }
        }
    }
}
    fun setGroups(groups: List<SymbolGroup>) {
        if (groups.isNotEmpty()) {
            this.groupsState = groups
        }
    }

    fun setUiSettings(settings: SymbolUiSettings) {
        this.uiSettingsState = settings
    }

    fun refreshData() {
        // Re-composition triggers automatically on state update
    }

    fun onHostResume() {}

    

@Composable
fun AdvancedSymbolInputToolbar(
    groups: List<SymbolGroup>,
    uiSettings: SymbolUiSettings,
    onSymbolClicked: (SymbolItem, isLong: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (groups.isEmpty()) return

    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { groups.size })

    var isExpanded by remember { mutableStateOf(uiSettings.rememberExpanded) }

    val cellHeight = 36.dp
    val gap = 4.dp
    val padding = 4.dp
    val collapsedRows = uiSettings.collapsedRows.coerceAtLeast(1)

    val cols = uiSettings.symbolsPerRow.coerceIn(1, 20)
    val maxRows = (groups.getOrNull(pagerState.currentPage)?.items?.size ?: 0).let { count ->
        ((count + cols - 1) / cols).coerceAtLeast(collapsedRows)
    }

    val collapsedHeight = (cellHeight * collapsedRows) + (gap * (collapsedRows - 1)) + (padding * 2)
    val expandedHeight = (cellHeight * maxRows) + (gap * (maxRows - 1)) + (padding * 2)

    val currentPagerHeight by animateDpAsState(
        targetValue = if (isExpanded) expandedHeight else collapsedHeight,
        label = "DrawerHeightAnimation"
    )

    val dragSensitivity = with(LocalDensity.current) { 32.dp.toPx() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    if (delta < -dragSensitivity) isExpanded = true
                    else if (delta > dragSensitivity) isExpanded = false
                }
            )
    ) {
        // Top Group Indicator Bar
        GroupIndicatorBar(
            groups = groups,
            selectedIndex = pagerState.currentPage,
            style = uiSettings.indicatorStyle,
            onGroupSelected = { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            }
        )

        // Symbol Grid Container
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(currentPagerHeight)
        ) { pageIndex ->
            SymbolPageGrid(
                items = groups[pageIndex].items,
                symbolsPerRow = cols,
                textSizeSp = uiSettings.symbolTextSizeSp,
                onSymbolClicked = onSymbolClicked
            )
        }

        // Bottom Page Indicator
        if (uiSettings.indicatorStyle == 1 || uiSettings.indicatorStyle == 4) {
            CompactPageIndicator(
                count = groups.size,
                selectedIndex = pagerState.currentPage,
                style = uiSettings.indicatorStyle,
                onPageSelected = { index ->
                    scope.launch { pagerState.animateScrollToPage(index) }
                }
            )
        }
    }
}

@Composable
private fun GroupIndicatorBar(
    groups: List<SymbolGroup>,
    selectedIndex: Int,
    style: Int,
    onGroupSelected: (Int) -> Unit
) {
    if (style == 1 || style == 2 || style == 4) return

    Column(modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(groups) { index, group ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                        .clickable { onGroupSelected(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = group.name,
                        fontSize = 12.sp,
                        color = if (isSelected) Color(0xFF6200EE) else Color.DarkGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.LightGray.copy(alpha = 0.5f))
        )
    }
}

@Composable
private fun SymbolPageGrid(
    items: List<SymbolItem>,
    symbolsPerRow: Int,
    textSizeSp: Int,
    onSymbolClicked: (SymbolItem, isLong: Boolean) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(symbolsPerRow.coerceIn(1, 20)),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 4.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(items) { item ->
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .combinedClickable(
                        onClick = { onSymbolClicked(item, false) },
                        onLongClick = {
                            if (item.longAction != null || item.longText != null) {
                                onSymbolClicked(item, true)
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.display,
                    fontSize = if (textSizeSp > 0) textSizeSp.sp else 16.sp,
                    color = Color(0xFF333333),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CompactPageIndicator(
    count: Int,
    selectedIndex: Int,
    style: Int,
    onPageSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(count) { index ->
            val isSelected = index == selectedIndex
            val size = if (isSelected) 8.dp else 6.dp
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(size)
                    .background(
                        color = if (isSelected) Color(0xFF6200EE) else Color.Gray.copy(alpha = 0.4f),
                        shape = if (style == 1) RoundedCornerShape(percent = 50) else RoundedCornerShape(1.dp)
                    )
                    .clickable { onPageSelected(index) }
            )
        }
    }
}
