package android.zero.studio.widget.editor.symbolinput

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
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

class AdvancedSymbolInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    private val indicatorScrollView = HorizontalScrollView(context).apply {
        overScrollMode = OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
    }
    private val indicatorContainer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val pager = SymbolPagerContainer(context)

    private var editor: CodeEditor? = null
    var onOpenManagerListener: (() -> Unit)? = null
    var followSystemIme: Boolean = false

    private val groups = mutableListOf<SymbolGroup>()
    private val pagerAdapter = SymbolPagerAdapter()
    private var uiSettings = SymbolUiSettings()

    private val rowHeightPx by lazy { dp(36) }
    private val collapsedExtraPaddingPx by lazy { dp(20) }
    private val itemHeightPx by lazy { dp(44) }
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
        indicatorScrollView.addView(indicatorContainer, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(indicatorScrollView)
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, collapsedHeightPx))
        pager.viewPager.adapter = pagerAdapter
        pager.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                syncIndicatorSelection(position)
                if (uiSettings.rememberLastPage && lastSavedPageIndex != position) {
                    lastSavedPageIndex = position
                    SymbolDataManager.setLastPageIndex(context, position)
                }
                recalculateHeights()
            }
        })
        refreshData()
        applyIndicatorReveal(0f)
    }

    fun setupWithBottomSheet(rootView: View, bottomSheet: View, followView: View? = null) = Unit
    fun bindEditor(editor: CodeEditor) { this.editor = editor }
    fun onHostResume() {
        val shouldExpand = uiSettings.rememberExpanded && SymbolDataManager.getLastExpanded(context)
        animateToHeight(if (shouldExpand) expandedHeightPx else collapsedHeightPx)
    }

    fun refreshData() {
        uiSettings = SymbolDataManager.getUiSettings(context)
        val newData = SymbolDataManager.loadData(context)
        groups.clear()
        groups.addAll(newData.filter { it.items.isNotEmpty() })
        if (groups.isEmpty()) {
            val defaults = SymbolDefaults.createFallbackGroups()
            groups.addAll(defaults)
            SymbolDataManager.saveData(context, defaults)
        }
        rebuildIndicators()
        pagerAdapter.notifyDataSetChanged()
        if (groups.isNotEmpty()) {
            val target = if (uiSettings.rememberLastPage) SymbolDataManager.getLastPageIndex(context).coerceIn(0, groups.lastIndex) else 0
            pager.viewPager.currentItem = target
            syncIndicatorSelection(target)
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
        val indicatorHeight = (indicatorScrollView.layoutParams.height).coerceAtLeast(0)
        measureChildWithMargins(indicatorScrollView, widthMeasureSpec, 0, heightMeasureSpec, 0)
        val pagerHeightSpec = MeasureSpec.makeMeasureSpec((pager.layoutParams.height).coerceAtLeast(collapsedHeightPx), MeasureSpec.EXACTLY)
        val pagerWidthSpec = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
        pager.measure(pagerWidthSpec, pagerHeightSpec)
        setMeasuredDimension(width, indicatorHeight + pager.measuredHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val indicatorHeight = indicatorScrollView.measuredHeight
        indicatorScrollView.layout(0, 0, r - l, indicatorHeight)
        pager.layout(0, indicatorHeight, r - l, indicatorHeight + pager.measuredHeight)
    }

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams = MarginLayoutParams(context, attrs)
    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams): LayoutParams = MarginLayoutParams(p)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { initialY = ev.rawY; lastY = ev.rawY; initialX = ev.rawX; isDragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - initialY
                val dx = ev.rawX - initialX
                if (!isDragging && kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)) {
                    isDragging = true; parent?.requestDisallowInterceptTouchEvent(true); return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { heightAnimator?.cancel(); initialY = event.rawY; lastY = event.rawY; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return super.onTouchEvent(event)
                val deltaY = event.rawY - lastY
                val next = (pager.layoutParams.height - deltaY.toInt()).coerceIn(collapsedHeightPx, expandedHeightPx)
                updatePagerHeight(next); lastY = event.rawY; return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    val mid = (collapsedHeightPx + expandedHeightPx) / 2
                    val target = if (pager.layoutParams.height >= mid) expandedHeightPx else collapsedHeightPx
                    if (uiSettings.rememberExpanded) SymbolDataManager.setLastExpanded(context, target == expandedHeightPx)
                    animateToHeight(target)
                }
                isDragging = false; return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun animateToHeight(target: Int) {
        val current = pager.layoutParams.height.coerceAtLeast(collapsedHeightPx)
        if (current == target) return
        heightAnimator?.cancel()
        heightAnimator = ValueAnimator.ofInt(current, target).apply {
            duration = 200
            addUpdateListener { updatePagerHeight(it.animatedValue as Int) }
            start()
        }
    }

    private fun updatePagerHeight(height: Int) {
        val clamped = height.coerceIn(collapsedHeightPx, expandedHeightPx)
        if (pager.layoutParams.height != clamped) {
            pager.layoutParams = pager.layoutParams.apply { this.height = clamped }
            requestLayout()
        }
        val fraction = (clamped - collapsedHeightPx).toFloat() / (expandedHeightPx - collapsedHeightPx).coerceAtLeast(1)
        applyIndicatorReveal(fraction)
    }

    private fun applyIndicatorReveal(fraction: Float) {
        val reveal = ((fraction - 0.08f) / 0.47f).coerceIn(0f, 1f)
        indicatorScrollView.alpha = reveal
        indicatorScrollView.translationY = (1f - reveal) * -6f * resources.displayMetrics.density
        indicatorScrollView.layoutParams = indicatorScrollView.layoutParams.apply { height = (dp(44) * reveal).roundToInt() }
        requestLayout()
    }

    private fun recalculateHeights() {
        collapsedHeightPx = rowHeightPx * uiSettings.collapsedRows.coerceAtLeast(1) + collapsedExtraPaddingPx
        val cols = uiSettings.symbolsPerRow.coerceIn(1, 20)
        val page = groups.getOrNull(pager.viewPager.currentItem)
        val rows = if (page == null) 2 else (page.items.size + cols - 1) / cols
        expandedHeightPx = (rows.coerceAtLeast(2) * itemHeightPx + dp(10)).coerceAtLeast(collapsedHeightPx + rowHeightPx)
        updatePagerHeight(pager.layoutParams.height.coerceIn(collapsedHeightPx, expandedHeightPx))
    }

    private fun rebuildIndicators() {
        indicatorContainer.removeAllViews()
        groups.forEachIndexed { index, group ->
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(6), dp(6), dp(6), dp(6))
                addView(TextView(context).apply {
                    text = group.name
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
                setOnClickListener { pager.viewPager.currentItem = index }
            }
            indicatorContainer.addView(chip, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
    }

    private fun syncIndicatorSelection(position: Int) {
        for (i in 0 until indicatorContainer.childCount) {
            indicatorContainer.getChildAt(i).alpha = if (i == position) 1f else 0.6f
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private inner class SymbolPagerAdapter : PagerAdapter() {
        override fun getCount(): Int = groups.size
        override fun isViewFromObject(view: View, `object`: Any): Boolean = view === `object`
        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val group = groups[position]
            val page = SymbolPageGridView(context, uiSettings) { item, long ->
                if (long) {
                    val action = item.longAction
                    if (action != null) editor?.let { SymbolActionExecutor.execute(it, action, item.longText, onOpenManagerListener) }
                } else {
                    editor?.let { SymbolActionExecutor.execute(it, item.shortAction, item.shortText, onOpenManagerListener) }
                }
            }
            page.submit(group.items)
            container.addView(page)
            return page
        }
        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) { container.removeView(`object` as View) }
        override fun getPageTitle(position: Int): CharSequence = groups[position].name
        override fun getItemPosition(`object`: Any): Int = POSITION_NONE
    }
}

private class SymbolPagerContainer(context: Context) : ViewGroup(context) {
    val viewPager = ViewPager(context).apply { overScrollMode = OVER_SCROLL_NEVER }
    init { addView(viewPager, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        viewPager.measure(MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { viewPager.layout(0,0,r-l,b-t) }
}

private class SymbolPageGridView(
    context: Context,
    private val settings: SymbolUiSettings,
    private val callback: (SymbolItem, Boolean) -> Unit
) : ViewGroup(context) {
    private val itemViews = mutableListOf<TextView>()
    fun submit(items: List<SymbolItem>) {
        removeAllViews(); itemViews.clear()
        items.forEach { item ->
            val tv = TextView(context).apply {
                text = item.display
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                minHeight = (36 * resources.displayMetrics.density).roundToInt()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, settings.symbolTextSizeSp.toFloat())
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,10,settings.symbolTextSizeSp.coerceAtLeast(12),1,TypedValue.COMPLEX_UNIT_SP)
                val bg = TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, bg, true); setBackgroundResource(bg.resourceId)
                setOnClickListener { callback(item, false) }
                setOnLongClickListener { callback(item, true); item.longAction != null }
            }
            addView(tv)
            itemViews.add(tv)
        }
        requestLayout()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cols = settings.symbolsPerRow.coerceIn(1, 20)
        val cellW = (w - paddingLeft - paddingRight) / cols
        val cellH = (44 * resources.displayMetrics.density).roundToInt()
        val childW = MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY)
        val childH = MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY)
        repeat(childCount) { getChildAt(it).measure(childW, childH) }
        val rows = (childCount + cols - 1) / cols
        setMeasuredDimension(w, paddingTop + paddingBottom + rows * cellH)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cols = settings.symbolsPerRow.coerceIn(1, 20)
        val cellW = (width - paddingLeft - paddingRight) / cols
        val cellH = (44 * resources.displayMetrics.density).roundToInt()
        repeat(childCount) { i ->
            val row = i / cols; val col = i % cols
            val left = paddingLeft + col * cellW; val top = paddingTop + row * cellH
            getChildAt(i).layout(left, top, left + cellW, top + cellH)
        }
    }
}
