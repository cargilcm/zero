package android.zero.studio.widget.editor.symbolinput

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import io.github.rosemoe.sora.widget.CodeEditor
import kotlin.math.roundToInt

/**
 * MT 风格的符号输入控件：
 * Root(ViewGroup)
 *  ├─ GroupIndicatorBar(HorizontalScrollView > LinearLayout > (LinearLayout > TextView))
 *  └─ SymbolPagerHost(ViewGroup > ViewPager > SymbolPageGridView)
 */
class AdvancedSymbolInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val indicatorBar = GroupIndicatorBar(context) { index -> pagerHost.setCurrentPage(index, true) }
    private val pagerHost = SymbolPagerHost(context)
    private val pageAdapter = SymbolPagerAdapter()

    private var editor: CodeEditor? = null
    var onOpenManagerListener: (() -> Unit)? = null
    var followSystemIme: Boolean = false

    private val groups = mutableListOf<SymbolGroup>()
    private var uiSettings = SymbolUiSettings()

    private val rowHeightPx by lazy { dp(36) }
    private val itemHeightPx by lazy { dp(44) }
    private val collapsedExtraPaddingPx by lazy { dp(20) }
    private var collapsedHeightPx = rowHeightPx * 2 + collapsedExtraPaddingPx
    private var expandedHeightPx = dp(220)

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }
    private var initialY = 0f
    private var initialX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var heightAnimator: ValueAnimator? = null
    private var lastSavedPageIndex = -1

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (SymbolDataManager.shouldTriggerUiRefresh(key)) refreshData()
    }

    init {
        addView(indicatorBar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        addView(pagerHost, LayoutParams(LayoutParams.MATCH_PARENT, collapsedHeightPx))
        pagerHost.bindAdapter(pageAdapter)
        pagerHost.onPageChanged = { page ->
            indicatorBar.setSelectedIndex(page)
            if (uiSettings.rememberLastPage && lastSavedPageIndex != page) {
                lastSavedPageIndex = page
                SymbolDataManager.setLastPageIndex(context, page)
            }
            recalculateHeights()
        }
        refreshData()
        applyIndicatorReveal(0f)
    }

    fun setupWithBottomSheet(rootView: View, bottomSheet: View, followView: View? = null) = Unit

    fun bindEditor(editor: CodeEditor) {
        this.editor = editor
    }

    fun onHostResume() {
        val shouldExpand = uiSettings.rememberExpanded && SymbolDataManager.getLastExpanded(context)
        animateToHeight(if (shouldExpand) expandedHeightPx else collapsedHeightPx)
    }

    fun refreshData() {
        uiSettings = SymbolDataManager.getUiSettings(context)

        groups.clear()
        groups.addAll(SymbolDataManager.loadData(context).filter { it.items.isNotEmpty() })
        if (groups.isEmpty()) {
            val defaults = SymbolDefaults.createFallbackGroups()
            groups.addAll(defaults)
            SymbolDataManager.saveData(context, defaults)
        }

        indicatorBar.submitGroups(groups)
        pageAdapter.notifyDataSetChanged()

        if (groups.isNotEmpty()) {
            val target = if (uiSettings.rememberLastPage) {
                SymbolDataManager.getLastPageIndex(context).coerceIn(0, groups.lastIndex)
            } else 0
            pagerHost.setCurrentPage(target, false)
            indicatorBar.setSelectedIndex(target)
            lastSavedPageIndex = target
        }

        recalculateHeights()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.getSharedPreferences("advanced_symbol_prefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(prefsListener)
    }

    override fun onDetachedFromWindow() {
        context.getSharedPreferences("advanced_symbol_prefs", Context.MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val indicatorHeight = indicatorBar.layoutParams.height.coerceAtLeast(0)
        measureChild(indicatorBar, widthMeasureSpec, MeasureSpec.makeMeasureSpec(indicatorHeight, MeasureSpec.EXACTLY))

        val pagerHeight = pagerHost.layoutParams.height.coerceAtLeast(collapsedHeightPx)
        pagerHost.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(pagerHeight, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(width, indicatorBar.measuredHeight + pagerHost.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val indicatorHeight = indicatorBar.measuredHeight
        indicatorBar.layout(0, 0, width, indicatorHeight)
        pagerHost.layout(0, indicatorHeight, width, indicatorHeight + pagerHost.measuredHeight)
    }

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                initialY = ev.rawY
                lastY = ev.rawY
                initialX = ev.rawX
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - initialY
                val dx = ev.rawX - initialX
                if (!isDragging && kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                    isDragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                heightAnimator?.cancel()
                initialY = event.rawY
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return super.onTouchEvent(event)
                val deltaY = event.rawY - lastY
                val nextHeight = (pagerHost.layoutParams.height - deltaY.toInt()).coerceIn(collapsedHeightPx, expandedHeightPx)
                updatePagerHeight(nextHeight)
                lastY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val midpoint = (collapsedHeightPx + expandedHeightPx) / 2
                    val target = if (pagerHost.layoutParams.height >= midpoint) expandedHeightPx else collapsedHeightPx
                    if (uiSettings.rememberExpanded) {
                        SymbolDataManager.setLastExpanded(context, target == expandedHeightPx)
                    }
                    animateToHeight(target)
                }
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateToHeight(targetHeight: Int) {
        val currentHeight = pagerHost.layoutParams.height.coerceAtLeast(collapsedHeightPx)
        if (currentHeight == targetHeight) return
        heightAnimator?.cancel()
        heightAnimator = ValueAnimator.ofInt(currentHeight, targetHeight).apply {
            duration = 200
            addUpdateListener { updatePagerHeight(it.animatedValue as Int) }
            start()
        }
    }

    private fun updatePagerHeight(height: Int) {
        val clamped = height.coerceIn(collapsedHeightPx, expandedHeightPx)
        if (pagerHost.layoutParams.height != clamped) {
            pagerHost.layoutParams = pagerHost.layoutParams.apply { this.height = clamped }
            requestLayout()
        }
        val fraction = (clamped - collapsedHeightPx).toFloat() / (expandedHeightPx - collapsedHeightPx).coerceAtLeast(1)
        applyIndicatorReveal(fraction)
    }

    private fun applyIndicatorReveal(fraction: Float) {
        val reveal = ((fraction - 0.08f) / 0.47f).coerceIn(0f, 1f)
        indicatorBar.applyReveal(reveal, dp(44))
        requestLayout()
    }

    private fun recalculateHeights() {
        collapsedHeightPx = rowHeightPx * uiSettings.collapsedRows.coerceAtLeast(1) + collapsedExtraPaddingPx
        val cols = uiSettings.symbolsPerRow.coerceIn(1, 20)
        val currentGroup = groups.getOrNull(pagerHost.currentPage)
        val rows = if (currentGroup == null) 2 else (currentGroup.items.size + cols - 1) / cols
        expandedHeightPx = (rows.coerceAtLeast(2) * itemHeightPx + dp(10)).coerceAtLeast(collapsedHeightPx + rowHeightPx)
        updatePagerHeight(pagerHost.layoutParams.height.coerceIn(collapsedHeightPx, expandedHeightPx))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private inner class SymbolPagerAdapter : PagerAdapter() {
        override fun getCount(): Int = groups.size
        override fun isViewFromObject(view: View, `object`: Any): Boolean = view === `object`

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val group = groups[position]
            val pageView = SymbolPageGridView(context) { item, isLong ->
                if (isLong) {
                    val action = item.longAction
                    if (action != null) {
                        editor?.let {
                            SymbolActionExecutor.execute(it, action, item.longText, onOpenManagerListener)
                        }
                    }
                } else {
                    editor?.let {
                        SymbolActionExecutor.execute(it, item.shortAction, item.shortText, onOpenManagerListener)
                    }
                }
            }
            pageView.updateConfig(uiSettings)
            pageView.submit(group.items)
            container.addView(pageView)
            return pageView
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            container.removeView(`object` as View)
        }

        override fun getItemPosition(`object`: Any): Int = POSITION_NONE
    }
}

private class GroupIndicatorBar(
    context: Context,
    private val onGroupClicked: (Int) -> Unit
) : ViewGroup(context) {

    private val scroll = HorizontalScrollView(context).apply {
        overScrollMode = OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
    }
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    init {
        scroll.addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun submitGroups(groups: List<SymbolGroup>) {
        row.removeAllViews()
        groups.forEachIndexed { index, group ->
            val item = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), dp(6))
                addView(TextView(context).apply {
                    text = group.name
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                })
                setOnClickListener { onGroupClicked(index) }
            }
            row.addView(item, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    fun setSelectedIndex(index: Int) {
        for (i in 0 until row.childCount) {
            row.getChildAt(i).alpha = if (i == index) 1f else 0.6f
        }
        row.getChildAt(index)?.let { child ->
            val center = child.left + child.width / 2
            scroll.smoothScrollTo((center - width / 2).coerceAtLeast(0), 0)
        }
    }

    fun applyReveal(fraction: Float, fullHeight: Int) {
        alpha = fraction
        translationY = (1f - fraction) * -6f * resources.displayMetrics.density
        layoutParams = layoutParams.apply { height = (fullHeight * fraction).roundToInt() }
        visibility = if (fraction <= 0f) View.INVISIBLE else View.VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        scroll.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        scroll.layout(0, 0, r - l, b - t)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}

private class SymbolPagerHost(context: Context) : ViewGroup(context) {
    private val pager = ViewPager(context).apply { overScrollMode = OVER_SCROLL_NEVER }
    var onPageChanged: ((Int) -> Unit)? = null
    val currentPage: Int get() = pager.currentItem

    init {
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                onPageChanged?.invoke(position)
            }
        })
    }

    fun bindAdapter(adapter: PagerAdapter) {
        pager.adapter = adapter
    }

    fun setCurrentPage(page: Int, smooth: Boolean) {
        pager.setCurrentItem(page, smooth)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        pager.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        pager.layout(0, 0, r - l, b - t)
    }
}

private class SymbolPageGridView(
    context: Context,
    private val onItemTriggered: (SymbolItem, Boolean) -> Unit
) : ViewGroup(context) {

    private var settings = SymbolUiSettings()

    fun updateConfig(settings: SymbolUiSettings) {
        this.settings = settings
    }

    fun submit(items: List<SymbolItem>) {
        removeAllViews()
        items.forEach { item ->
            val tv = TextView(context).apply {
                text = item.display
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minHeight = dp(36)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.symbolTextSizeSp.toFloat())
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    this,
                    10,
                    settings.symbolTextSizeSp.coerceAtLeast(12),
                    1,
                    TypedValue.COMPLEX_UNIT_SP
                )
                val bg = TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true)
                setBackgroundResource(bg.resourceId)
                setOnClickListener { onItemTriggered(item, false) }
                setOnLongClickListener {
                    if (item.longAction != null) {
                        onItemTriggered(item, true)
                        true
                    } else {
                        false
                    }
                }
            }
            addView(tv)
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cols = settings.symbolsPerRow.coerceIn(1, 20)
        val cellWidth = (width - paddingLeft - paddingRight) / cols
        val cellHeight = dp(44)
        val cw = MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY)
        val ch = MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)

        repeat(childCount) { getChildAt(it).measure(cw, ch) }

        val rows = (childCount + cols - 1) / cols
        val totalHeight = paddingTop + paddingBottom + rows * cellHeight
        setMeasuredDimension(width, totalHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cols = settings.symbolsPerRow.coerceIn(1, 20)
        val cellWidth = (width - paddingLeft - paddingRight) / cols
        val cellHeight = dp(44)

        repeat(childCount) { index ->
            val row = index / cols
            val col = index % cols
            val left = paddingLeft + col * cellWidth
            val top = paddingTop + row * cellHeight
            getChildAt(index).layout(left, top, left + cellWidth, top + cellHeight)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
}
