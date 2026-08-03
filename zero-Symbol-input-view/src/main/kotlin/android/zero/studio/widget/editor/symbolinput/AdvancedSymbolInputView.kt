@file:OptIn(ExperimentalFoundationApi::class)
package android.zero.studio.widget.editor.symbolinput

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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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

    // Expanded / Collapsed state management
    var isExpanded by remember { mutableStateOf(uiSettings.rememberExpanded) }

    // Grid dimension calculations
    val cellHeight = 28.dp
    val gap = 2.dp
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

    val dragSensitivity = with(LocalDensity.current) { 48.dp.toPx() }

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
        // 1. Top Indicator Bar (Group Selection)
        GroupIndicatorBar(
            groups = groups,
            selectedIndex = pagerState.currentPage,
            style = uiSettings.indicatorStyle,
            onGroupSelected = { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            }
        )

        // 2. Pager Content Container
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

        // 3. Bottom Compact Indicator
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

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        itemsIndexed(groups) { index, group ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .alpha(if (isSelected) 1f else 0.6f)
                    .clickable { onGroupSelected(index) },
                contentAlignment = Alignment.Center
            ) {
                if (style == 3) {
                    Box(
                        modifier = Modifier
                            .size(width = 22.dp, height = 3.dp)
                            .background(
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                } else {
                    Text(
                        text = group.name,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SymbolPageGrid(
    items: List<SymbolItem>,
    symbolsPerRow: Int,
    textSizeSp: Int,
    onSymbolClicked: (SymbolItem, isLong: Boolean) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(symbolsPerRow.coerceIn(1, 20)),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(items) { item ->
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .combinedClickable(
                        onClick = { onSymbolClicked(item, false) },
                        onLongClick = {
                            if (item.longAction != null) {
                                onSymbolClicked(item, true)
                            }
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.display,
                    fontSize = textSizeSp.sp,
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
            .height(14.dp),
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
                    .alpha(if (isSelected) 1f else 0.55f)
                    .background(
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                        shape = if (style == 1) RoundedCornerShape(percent = 50) else RoundedCornerShape(1.dp)
                    )
                    .clickable { onPageSelected(index) }
            )
        }
    }
}
