package com.widgetflow.app.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.widgetflow.app.R
import com.widgetflow.app.databinding.ActivityWidgetBinding
import com.widgetflow.app.model.DataSource
import com.widgetflow.app.model.Element
import com.widgetflow.app.model.WidgetConfig
import com.widgetflow.app.model.renderTemplate
import com.widgetflow.app.storage.ConfigStore
import com.widgetflow.app.storage.SourceStore
import com.widgetflow.app.util.CrashLog
import com.widgetflow.app.widget.FlowWidgetProvider1x1
import com.widgetflow.app.widget.FlowWidgetProvider1x2
import com.widgetflow.app.widget.FlowWidgetProvider2x1
import com.widgetflow.app.widget.FlowWidgetProvider2x2
import com.widgetflow.app.widget.FlowWidgetProvider4x2
import com.widgetflow.app.widget.PinResultReceiver
import com.widgetflow.app.widget.RefreshScheduler
import com.widgetflow.app.widget.WidgetUpdater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小部件编辑器：只负责展示层。
 * 每个元素可选择一个数据源（Element.sourceId），模板中引用该数据源抽取出的 {别名}。
 * 一个组件可同时引用多个数据源。
 */
class WidgetEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_WIDGET_ID = "widgetId"
        private val REFRESH_MINUTES = intArrayOf(1, 5, 15, 30, 60, 360, WidgetConfig.FREQ_DAILY_8)
        private val ELEMENT_COLORS = listOf(
            "#FFFFFF", "#E6E6E6", "#1E1E1E", "#4CAF50", "#757575",
            "#C77E17", "#E53935", "#BDBDBD", "#9AA3BC", "#000000"
        )
        private val BG_COLORS = listOf(
            "#FFFFFF", "#303030", "#2A2A2A", "#000000", "#1E1E1E",
            "#4CAF50", "#757575", "#C77E17", "#E53935", "#9AA3BC"
        )
    }

    private lateinit var binding: ActivityWidgetBinding
    private lateinit var draft: WidgetConfig
    private var sources: MutableList<DataSource> = mutableListOf()
    private var editingExisting = false
    private var suppress = true
    private var sel = -1

    // 拖拽状态
    private var dragX = 0f
    private var dragY = 0f
    private var dragL = 0
    private var dragT = 0
    /** 是否处于长按拖动中（长按元素后进入，避免与页面滚动冲突） */
    private var dragActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWidgetBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val widgetId = intent.getStringExtra(EXTRA_WIDGET_ID)
        editingExisting = widgetId != null
        draft = if (widgetId != null) {
            ConfigStore.find(this, widgetId) ?: WidgetConfig(id = WidgetConfig.newId())
        } else {
            WidgetConfig(id = WidgetConfig.newId())
        }

        sources = SourceStore.all(this)
        if (sources.isEmpty()) {
            Toast.makeText(this, R.string.hint_no_source_widget, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.wzTitle.text =
            getString(if (editingExisting) R.string.widget_title_edit else R.string.widget_title_new)
        binding.wzSubtitle.text = getString(R.string.wz_step4_subtitle)
        binding.inpWidgetName.setText(draft.name)

        setupSourceSpinner()
        setupStep4()
        suppress = false

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSaveWidget.setOnClickListener { save() }

        // 没有元素时按第一个数据源自动生成默认元素
        if (draft.elements.isEmpty()) buildDefaultElements()
        renderEditor()
    }

    // ================= 数据源选择器 =================

    /** 数据源选择：点击字段弹出可滚动单选列表（不再用下拉框，避免滑不动） */
    private fun setupSourceSpinner() {
        binding.step4.spinSource.setOnClickListener {
            if (sel in draft.elements.indices) {
                val idx = sources.indexOfFirst { it.id == draft.elements[sel].sourceId }
                showSourceSelectDialog(idx.coerceAtLeast(0))
            }
        }
    }

    /** 单选数据源（ListView 原生滚动） */
    private fun showSourceSelectDialog(initialIdx: Int) {
        val names = sources.map { it.name.ifBlank { "未命名数据源" } }
        val lv = android.widget.ListView(this).apply {
            adapter = ArrayAdapter(
                this@WidgetEditorActivity,
                android.R.layout.simple_list_item_single_choice,
                names
            )
            choiceMode = android.widget.ListView.CHOICE_MODE_SINGLE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.6f).toInt()
            )
            if (initialIdx in names.indices) setItemChecked(initialIdx, true)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.label_element_source)
            .setView(lv)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_ok) { _, _ ->
                val pos = lv.checkedItemPosition
                if (pos >= 0 && sel in draft.elements.indices) {
                    draft.elements[sel].sourceId = sources[pos].id
                    updatePropAliases()
                    rebuildCanvas()
                    updatePanel()
                    syncSourceSpinner()
                }
            }
            .show()
    }

    /** 把当前选中元素的数据源同步到字段文本 */
    private fun syncSourceSpinner() {
        val el = draft.elements.getOrNull(sel) ?: return
        val idx = sources.indexOfFirst { it.id == el.sourceId }
        val name = if (idx >= 0) sources[idx].name.ifBlank { "未命名数据源" }
        else "未绑定 · 点击选择"
        binding.step4.spinSource.text = name
        binding.step4.spinSource.isEnabled = true
        if (idx < 0 && sel in draft.elements.indices) {
            // 元素引用的数据源已不存在：绑定到第一个数据源
            sources.getOrNull(0)?.let { draft.elements[sel].sourceId = it.id }
        }
    }

    // ================= 编辑器 =================

    private fun setupStep4() {
        binding.step4.seekFont.max = 9
        binding.step4.seekFont.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean
                ) {
                    val size = 9 + progress
                    binding.step4.fontValue.text = size.toString()
                    if (fromUser && sel in draft.elements.indices) {
                        draft.elements[sel].fontSize = size
                        rebuildCanvas()
                    }
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            }
        )

        // 背景圆角：0..8 → 0..64dp，拉到最右为圆形
        binding.step4.seekCorner.max = 8
        binding.step4.seekCorner.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(
                    sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean
                ) {
                    binding.step4.cornerValue.text = cornerLabel(progress * 8)
                    if (fromUser) {
                        draft.cornerRadius = progress * 8
                        rebuildCanvas()
                    }
                }

                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            }
        )

        binding.step4.inpTemplate.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!suppress && sel in draft.elements.indices) {
                    draft.elements[sel].template = s?.toString() ?: ""
                    rebuildCanvas()
                }
            }
        })

        binding.step4.btnSize1x1.setOnClickListener { setSize("1x1") }
        binding.step4.btnSize1x2.setOnClickListener { setSize("1x2") }
        binding.step4.btnSize2x1.setOnClickListener { setSize("2x1") }
        binding.step4.btnSize2x2.setOnClickListener { setSize("2x2") }
        binding.step4.btnSize4x2.setOnClickListener { setSize("4x2") }

        binding.step4.btnAddElement.setOnClickListener {
            if (draft.elements.size >= WidgetConfig.MAX_ELEMENTS) {
                Toast.makeText(this, "每组件最多 ${WidgetConfig.MAX_ELEMENTS} 个元素", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showAddSourceDialog()
        }

        binding.step4.btnDeleteElement.setOnClickListener {
            if (draft.elements.size <= 1) {
                Toast.makeText(this, R.string.toast_need_element, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            draft.elements.removeAt(sel)
            sel = 0
            renderEditor()
        }

        binding.step4.btnResetSize.setOnClickListener {
            if (sel in draft.elements.indices) {
                draft.elements[sel].width = 0f
                draft.elements[sel].height = 0f
                rebuildCanvas()
                updatePanel()
            }
        }

        binding.step4.btnBold.setOnClickListener {
            if (sel in draft.elements.indices) {
                draft.elements[sel].bold = !draft.elements[sel].bold
                updateStyleButtons()
                rebuildCanvas()
            }
        }
        binding.step4.btnItalic.setOnClickListener {
            if (sel in draft.elements.indices) {
                draft.elements[sel].italic = !draft.elements[sel].italic
                updateStyleButtons()
                rebuildCanvas()
            }
        }

        ELEMENT_COLORS.forEach { hex ->
            val v = View(this)
            val lp = LinearLayout.LayoutParams(30.dp(), 30.dp())
            lp.rightMargin = 8.dp()
            v.layoutParams = lp
            v.background = getDrawable(R.drawable.color_swatch)
            v.background?.setTint(parseColorSafe(hex))
            v.setOnClickListener {
                if (sel in draft.elements.indices) {
                    draft.elements[sel].color = hex
                    rebuildCanvas()
                    updatePanel()
                }
            }
            v.tag = hex
            binding.step4.boxColors.addView(v)
        }

        buildBgColorSwatches()

        val refreshOptions = resources.getStringArray(R.array.refresh_options)
        val cur = REFRESH_MINUTES.indexOf(draft.refreshMinutes).coerceAtLeast(1)
        binding.step4.spinRefresh.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, refreshOptions
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.step4.spinRefresh.setSelection(cur)
        binding.step4.spinRefresh.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long
                ) {
                    REFRESH_MINUTES.getOrNull(pos)?.let { draft.refreshMinutes = it }
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }
    }

    private fun setSize(size: String) {
        if (draft.size == size) return
        draft.size = size
        applyCanvasWidth()
        updateSizeButtons()
    }

    private fun buildBgColorSwatches() {
        binding.step4.boxBgColors.removeAllViews()
        BG_COLORS.forEach { hex ->
            val wrap = FrameLayout(this)
            val wlp = LinearLayout.LayoutParams(36.dp(), 36.dp())
            wlp.rightMargin = 8.dp()
            wrap.layoutParams = wlp
            val active = draft.bgColor.equals(hex, true)
            wrap.background = if (active) {
                getDrawable(R.drawable.bg_swatch_selected)
            } else {
                null
            }
            val v = View(this)
            val vlp = FrameLayout.LayoutParams(30.dp(), 30.dp())
            vlp.gravity = Gravity.CENTER
            v.layoutParams = vlp
            v.background = getDrawable(R.drawable.color_swatch)
            v.background?.setTint(parseColorSafe(hex))
            v.setOnClickListener {
                draft.bgColor = if (draft.bgColor.equals(hex, true)) "" else hex
                rebuildCanvas()
                buildBgColorSwatches()
            }
            wrap.addView(v)
            binding.step4.boxBgColors.addView(wrap)
        }
        binding.step4.btnBgDefault.setOnClickListener {
            draft.bgColor = ""
            rebuildCanvas()
            buildBgColorSwatches()
            Toast.makeText(this, R.string.btn_bg_default, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSizeButtons() {
        val active = getColor(R.color.accent)
        val activeBg = getColor(R.color.accent_light)
        val inactive = getColor(R.color.muted)
        fun mark(b: android.widget.Button, on: Boolean) {
            b.setTextColor(if (on) active else inactive)
            b.setBackgroundColor(if (on) activeBg else Color.TRANSPARENT)
        }
        mark(binding.step4.btnSize1x1, draft.size == "1x1")
        mark(binding.step4.btnSize1x2, draft.size == "1x2")
        mark(binding.step4.btnSize2x1, draft.size == "2x1")
        mark(binding.step4.btnSize2x2, draft.size == "2x2")
        mark(binding.step4.btnSize4x2, draft.size == "4x2")
    }

    private fun applyCanvasWidth() {
        val canvas = binding.step4.canvas
        canvas.post {
            val wrap = canvas.parent as? FrameLayout ?: return@post
            val availW = wrap.width
            val (w, h) = canvasSizeFor(draft.size, availW)
            canvas.layoutParams.width = w
            canvas.layoutParams.height = h
            canvas.requestLayout()
        }
    }

    /** 画布宽高：尽量加宽便于拖动，比例与桌面 widget_info 一致 */
    private fun canvasSizeFor(size: String, availW: Int): Pair<Int, Int> {
        val screenH = resources.displayMetrics.heightPixels
        return when (size) {
            "1x1" -> {
                val w = (availW * 0.55f).toInt()
                Pair(w, w)
            }
            "1x2" -> {
                val w = (availW * 0.4f).toInt()
                Pair(w, (w * 110f / 40f).toInt().coerceAtMost((screenH * 0.7f).toInt()))
            }
            "2x1" -> {
                val w = availW
                Pair(w, (w * 40f / 90f).toInt())
            }
            "2x2" -> {
                val w = availW
                Pair(w, (w * 110f / 140f).toInt())
            }
            else -> { // 4x2
                val w = availW
                Pair(w, (w * 110f / 250f).toInt())
            }
        }
    }

    /** 某元素的预览文本：取其数据源的 aliasMap */
    private fun elementPreviewMap(el: Element): Map<String, String> {
        val src = SourceStore.find(this, el.sourceId)
        return src?.aliasMap ?: emptyMap()
    }

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    /** 按第一个数据源的规则生成默认元素（不含时间元素；界面为深色底，文字用浅色） */
    private fun buildDefaultElements() {
        val ink = "#E6E6E6"
        val muted = "#BDBDBD"
        val first = sources.firstOrNull() ?: return
        draft.elements.clear()
        first.rules.getOrNull(0)?.let {
            draft.elements.add(Element("『{${it.alias}}』", 13, ink, 6f, 12f, sourceId = first.id))
        }
        first.rules.getOrNull(1)?.let {
            draft.elements.add(Element("—— {${it.alias}}", 9, muted, 6f, 64f, sourceId = first.id))
        }
        if (draft.elements.isEmpty()) {
            draft.elements.add(Element("来自「${first.name}」", 13, ink, 6f, 12f, sourceId = first.id))
        }
    }

    /** 弹窗勾选要添加的数据源（可多选），ListView 原生滚动 */
    private fun showAddSourceDialog() {
        if (sources.isEmpty()) {
            Toast.makeText(this, R.string.hint_no_source_widget, Toast.LENGTH_SHORT).show()
            return
        }
        val names = sources.map { it.name.ifBlank { "未命名数据源" } }
        val lv = android.widget.ListView(this).apply {
            adapter = ArrayAdapter(
                this@WidgetEditorActivity,
                android.R.layout.simple_list_item_multiple_choice,
                names
            )
            choiceMode = android.widget.ListView.CHOICE_MODE_MULTIPLE
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.6f).toInt()
            )
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.add_source_dialog_title)
            .setView(lv)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.add_source_dialog_ok) { _, _ ->
                val checked = BooleanArray(sources.size) { lv.isItemChecked(it) }
                addElementsForSelectedSources(checked)
            }
            .show()
    }

    /** 为每个勾选的数据源添加一个文本元素 */
    private fun addElementsForSelectedSources(checked: BooleanArray) {
        val ink = "#E6E6E6"
        var added = 0
        sources.forEachIndexed { i, src ->
            if (!checked[i]) return@forEachIndexed
            if (draft.elements.size >= WidgetConfig.MAX_ELEMENTS) {
                Toast.makeText(
                    this, "每组件最多 ${WidgetConfig.MAX_ELEMENTS} 个元素", Toast.LENGTH_SHORT
                ).show()
                return
            }
            val alias = src.rules.getOrNull(0)?.alias
            val template = if (alias != null) "『{$alias}』" else "来自「${src.name}」"
            val x = 6f + (draft.elements.size % 3) * 28f
            val y = 12f + (draft.elements.size / 3) * 44f
            draft.elements.add(
                Element(
                    template = template,
                    fontSize = 13,
                    color = ink,
                    x = x, y = y,
                    sourceId = src.id
                )
            )
            added++
        }
        if (added > 0) {
            sel = draft.elements.size - 1
            renderEditor()
            Toast.makeText(
                this, getString(R.string.toast_sources_added, added), Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun renderEditor() {
        if (draft.elements.isEmpty()) buildDefaultElements()
        if (sel !in draft.elements.indices) sel = 0
        syncSourceSpinner()
        updateSizeButtons()
        applyCanvasWidth()
        rebuildCanvas()
        renderChips()
        updatePanel()
        syncCornerSeekbar()
    }

    /** 把当前圆角值同步到滑块（-1 默认按 20dp 显示） */
    private fun syncCornerSeekbar() {
        val r = if (draft.cornerRadius >= 0) draft.cornerRadius else 20
        val progress = (r / 8).coerceIn(0, 8)
        binding.step4.seekCorner.progress = progress
        binding.step4.cornerValue.text = cornerLabel(progress * 8)
    }

    /** 圆角文案：0=方形，64=圆形，中间显示 dp */
    private fun cornerLabel(radius: Int): String = when (radius) {
        0 -> getString(R.string.corner_square)
        64 -> getString(R.string.corner_circle)
        else -> "${radius}dp"
    }

    private fun rebuildCanvas() {
        val canvas = binding.step4.canvas
        canvas.removeAllViews()
        if (draft.bgColor.isNotBlank() || draft.cornerRadius >= 0) {
            val color = if (draft.bgColor.isNotBlank()) parseColorSafe(draft.bgColor)
            else getColor(R.color.widget_bg_color)
            val radiusDp = if (draft.cornerRadius >= 0) draft.cornerRadius else 20
            canvas.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(color)
                cornerRadius = radiusDp.dp().toFloat()
            }
        } else {
            canvas.background = getDrawable(R.drawable.widget_bg)
        }
        val time = timeNow()
        draft.elements.forEachIndexed { i, el ->
            val box = FrameLayout(this)
            box.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            box.tag = i

            val tv = TextView(this)
            tv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tv.text = renderTemplate(el.template, elementPreviewMap(el), time)
            tv.textSize = el.fontSize.toFloat()
            tv.setTypeface(null, el.typefaceStyle())
            tv.setTextColor(parseColorSafe(el.color))
            tv.maxLines = 4
            if (i == sel) tv.setBackgroundColor(0x264CAF50)
            box.addView(tv)

            val handle = View(this)
            handle.layoutParams = FrameLayout.LayoutParams(
                16.dp(), 16.dp(), Gravity.BOTTOM or Gravity.END
            )
            handle.background = ContextCompat.getDrawable(this, R.drawable.color_swatch)?.mutate()
            handle.background?.setTint(getColor(R.color.accent))
            handle.visibility = if (i == sel) View.VISIBLE else View.GONE
            box.addView(handle)

            attachDrag(box, el, i)
            attachResize(handle, tv, el)
            canvas.addView(box)
            positionView(box, tv, el)
        }
    }

    private fun positionView(box: View, tv: TextView, el: Element) {
        val canvas = binding.step4.canvas
        canvas.post {
            val lp = box.layoutParams as FrameLayout.LayoutParams
            lp.leftMargin = (el.x / 100f * canvas.width).toInt()
            lp.topMargin = (el.y / 100f * canvas.height).toInt()
            val tvLp = tv.layoutParams as FrameLayout.LayoutParams
            tvLp.width = if (el.width > 0f) (el.width / 100f * canvas.width).toInt().coerceAtLeast(20)
            else ViewGroup.LayoutParams.WRAP_CONTENT
            tvLp.height = if (el.height > 0f) (el.height / 100f * canvas.height).toInt().coerceAtLeast(20)
            else ViewGroup.LayoutParams.WRAP_CONTENT
            tv.requestLayout()
            box.requestLayout()
        }
    }

    /** 元素拖动：点按选中，长按进入拖动（不吞掉触摸，页面可正常滚动） */
    private fun attachDrag(box: View, el: Element, index: Int) {
        box.setOnLongClickListener {
            selectElement(index, rebuild = false)
            dragActive = true
            box.parent?.requestDisallowInterceptTouchEvent(true)
            true
        }
        box.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectElement(index, rebuild = false)
                    dragX = event.rawX
                    dragY = event.rawY
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    dragL = lp.leftMargin
                    dragT = lp.topMargin
                    dragActive = false
                    // 不消费 DOWN：让外层页面可以正常滚动；长按后再拖动元素
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragActive) return@setOnTouchListener false
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    val w = binding.step4.canvas.width
                    val h = binding.step4.canvas.height
                    lp.leftMargin = (dragL + (event.rawX - dragX).toInt())
                        .coerceIn(0, (w - v.width).coerceAtLeast(0))
                    lp.topMargin = (dragT + (event.rawY - dragY).toInt())
                        .coerceIn(0, (h - v.height).coerceAtLeast(0))
                    v.requestLayout()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragActive) {
                        val w = binding.step4.canvas.width
                        val h = binding.step4.canvas.height
                        if (w > 0 && h > 0) {
                            val lp = v.layoutParams as FrameLayout.LayoutParams
                            el.x = lp.leftMargin * 100f / w
                            el.y = lp.topMargin * 100f / h
                        }
                        dragActive = false
                        updatePanel()
                    }
                    v.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragActive = false
                    false
                }
                else -> false
            }
        }
    }

    private fun attachResize(handle: View, tv: TextView, el: Element) {
        var sx = 0f
        var sy = 0f
        var startW = 0
        var startH = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = event.rawX
                    sy = event.rawY
                    val w = binding.step4.canvas.width
                    val h = binding.step4.canvas.height
                    startW = if (el.width > 0f) (el.width / 100f * w).toInt()
                    else tv.width.coerceAtLeast(30)
                    startH = if (el.height > 0f) (el.height / 100f * h).toInt()
                    else tv.height.coerceAtLeast(20)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - sx).toInt()
                    val dy = (event.rawY - sy).toInt()
                    val w = binding.step4.canvas.width
                    val h = binding.step4.canvas.height
                    if (w > 0 && h > 0) {
                        val nw = (startW + dx).coerceAtLeast(24)
                        val nh = (startH + dy).coerceAtLeast(20)
                        el.width = nw * 100f / w
                        el.height = nh * 100f / h
                        val lp = tv.layoutParams as FrameLayout.LayoutParams
                        lp.width = nw
                        lp.height = nh
                        tv.requestLayout()
                        updatePanel()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateHandles() {
        val canvas = binding.step4.canvas
        for (i in 0 until canvas.childCount) {
            val box = canvas.getChildAt(i) as? FrameLayout ?: continue
            val tag = box.tag as? Int ?: continue
            (box.getChildAt(0) as? TextView)?.let { tv ->
                tv.setBackgroundColor(if (tag == sel) 0x264CAF50 else Color.TRANSPARENT)
            }
            val handle = box.getChildAt(1) ?: continue
            handle.visibility = if (tag == sel) View.VISIBLE else View.GONE
        }
    }

    private fun renderChips() {
        val box = binding.step4.boxChips
        box.removeAllViews()
        draft.elements.forEachIndexed { i, el ->
            val chip = TextView(this)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.rightMargin = 6.dp()
            chip.layoutParams = lp
            val label = try {
                el.template.replace(Regex("\\{\\w+\\}"), "").take(6).ifBlank { "元素" }
            } catch (t: Throwable) {
                "元素"
            }
            chip.text = "${i + 1}·$label"
            chip.setPadding(12.dp(), 4.dp(), 12.dp(), 4.dp())
            chip.textSize = 12f
            if (i == sel) {
                chip.setBackgroundResource(R.drawable.chip_on)
                chip.setTextColor(getColor(R.color.accent))
            } else {
                chip.setBackgroundResource(R.drawable.chip_off)
                chip.setTextColor(getColor(R.color.muted))
            }
            chip.setOnClickListener { selectElement(i) }
            box.addView(chip)
        }
    }

    private fun selectElement(i: Int, rebuild: Boolean = true) {
        sel = i
        renderChips()
        syncSourceSpinner()
        updatePanel()
        if (rebuild) rebuildCanvas() else updateHandles()
    }

    /** 更新可用别名提示：来自当前元素数据源的规则 */
    private fun updatePropAliases() {
        val el = draft.elements.getOrNull(sel)
        val src = el?.let { SourceStore.find(this, it.sourceId) }
        val aliases = src?.rules?.map { "{${it.alias}}" } ?: emptyList()
        binding.step4.propAliases.text =
            "可用: " + (aliases + "{time}").joinToString(" ")
    }

    /** 更新 B/I 样式按钮高亮（绿=开启，灰=关闭） */
    private fun updateStyleButtons() {
        val el = draft.elements.getOrNull(sel)
        val active = getColor(R.color.accent)
        val activeBg = getColor(R.color.accent_light)
        val inactive = getColor(R.color.muted)
        fun mark(b: android.widget.Button, on: Boolean) {
            b.setTextColor(if (on) active else inactive)
            b.setBackgroundColor(if (on) activeBg else Color.TRANSPARENT)
        }
        mark(binding.step4.btnBold, el?.bold == true)
        mark(binding.step4.btnItalic, el?.italic == true)
    }

    private fun updatePanel() {
        val has = sel in draft.elements.indices
        val el = draft.elements.getOrNull(sel)
        binding.step4.inpTemplate.isEnabled = has
        binding.step4.seekFont.isEnabled = has
        binding.step4.spinSource.isEnabled = has
        binding.step4.btnDeleteElement.isEnabled = has
        binding.step4.btnBold.isEnabled = has
        binding.step4.btnItalic.isEnabled = has
        binding.step4.propName.text = if (has && el != null) {
            val src = SourceStore.find(this, el.sourceId)
            val srcName = src?.name?.ifBlank { "未命名数据源" } ?: "未绑定"
            val sizeTxt = if (el.hasExplicitSize()) {
                " · 尺寸 ${el.width.toInt()}% x ${el.height.toInt()}%"
            } else {
                " · 自适应"
            }
            "元素 ${sel + 1} · $srcName · ${el.x.toInt()}%,${el.y.toInt()}%$sizeTxt"
        } else {
            "未选中元素 · 点击画布或上方标签选择"
        }
        updatePropAliases()
        if (el != null) {
            if (!binding.step4.inpTemplate.hasFocus()) {
                suppress = true
                binding.step4.inpTemplate.setText(el.template)
                suppress = false
            }
            binding.step4.seekFont.progress = (el.fontSize - 9).coerceIn(0, 9)
            binding.step4.fontValue.text = el.fontSize.toString()
        }
        for (i in 0 until binding.step4.boxColors.childCount) {
            val v = binding.step4.boxColors.getChildAt(i)
            val active = el != null && v.tag == el.color
            v.scaleX = if (active) 1.2f else 1f
            v.scaleY = if (active) 1.2f else 1f
        }
        updateStyleButtons()
    }

    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // ================= 保存 =================

    private fun save() {
        try {
            saveInner()
        } catch (t: Throwable) {
            CrashLog.e(this, "save", t)
            Toast.makeText(this, "保存出错，请查看日志", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveInner() {
        draft.name = binding.inpWidgetName.text.toString().trim()
        if (draft.name.isBlank()) {
            draft.name = draft.elements.mapNotNull { el ->
                SourceStore.find(this, el.sourceId)?.name?.takeIf { it.isNotBlank() }
            }.distinct().take(2).joinToString(" + ").ifBlank { "桌面小部件" }
        }
        if (draft.elements.isEmpty()) {
            Toast.makeText(this, R.string.toast_need_element, Toast.LENGTH_SHORT).show()
            return
        }
        // 保证每个元素都有有效数据源
        val ids = sources.map { it.id }.toSet()
        draft.elements.forEach { el ->
            if (el.sourceId.isBlank() || !ids.contains(el.sourceId)) {
                sources.firstOrNull()?.let { el.sourceId = it.id }
            }
        }
        ConfigStore.save(this, draft)
        RefreshScheduler.ensureScheduled(this)
        CrashLog.write(
            this, "save",
            "widget name=${draft.name} elements=${draft.elements.size} size=${draft.size} " +
                "widgetIds=${draft.widgetIds.size} srcIds=${draft.elements.map { it.sourceId }}"
        )

        if (draft.widgetIds.isNotEmpty()) {
            draft.widgetIds.forEach { WidgetUpdater.render(this, it) }
            Toast.makeText(this, getString(R.string.toast_saved, draft.name), Toast.LENGTH_SHORT).show()
            finish()
        } else {
            requestPinWidget()
        }
    }

    /** 保存新小部件后请求固定到桌面（API 26+，需启动器支持） */
    private fun requestPinWidget() {
        try {
            requestPinInner()
        } catch (t: Throwable) {
            CrashLog.e(this, "requestPinWidget", t)
            Toast.makeText(this, "已保存，请到桌面手动添加小组件", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun requestPinInner() {
        val awm = AppWidgetManager.getInstance(this)
        val provider = ComponentName(this, providerForSize(draft.size))
        if (awm.isRequestPinAppWidgetSupported) {
            val before = awm.getAppWidgetIds(provider).toSet()
            val callback = PendingIntent.getBroadcast(
                this, 40001 + (draft.id.hashCode() and 0xFFFF),
                Intent(this, PinResultReceiver::class.java).putExtra("configId", draft.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val ok = awm.requestPinAppWidget(provider, null, callback)
            CrashLog.write(this, "requestPin", "supported=true ok=$ok size=${draft.size}")
            schedulePinVerify(provider, draft.id, before)
            Toast.makeText(
                this, "已保存，请在桌面确认添加「${draft.name}」",
                Toast.LENGTH_LONG
            ).show()
        } else {
            CrashLog.write(this, "requestPin", "supported=false")
            Toast.makeText(
                this,
                "已保存。请在桌面长按 → 小组件 → WidgetFlow 手动添加",
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }

    /** 轮询 requestPinAppWidget 后新出现的组件 ID，自动关联配置并渲染（兼容 -1 回调） */
    private fun schedulePinVerify(provider: ComponentName, configId: String, before: Set<Int>) {
        val app = applicationContext
        val check = Runnable {
            try {
                val awm = AppWidgetManager.getInstance(app)
                val now = awm.getAppWidgetIds(provider).toSet()
                val newIds = now - before
                CrashLog.write(app, "pinVerify", "new=${newIds.size} ids=$newIds")
                newIds.forEach { wid ->
                    val c = ConfigStore.find(app, configId) ?: return@forEach
                    if (wid >= 0 && !c.widgetIds.contains(wid)) {
                        c.widgetIds.add(wid)
                        ConfigStore.save(app, c)
                        WidgetUpdater.updateNow(app, wid)
                        CrashLog.write(app, "pinVerify", "关联成功 widget=$wid config=$configId")
                    }
                }
            } catch (t: Throwable) {
                CrashLog.e(app, "pinVerify", t)
            }
        }
        val h = Handler(Looper.getMainLooper())
        h.postDelayed(check, 6000)
        h.postDelayed(check, 15000)
        h.postDelayed(check, 30000)
        h.postDelayed(check, 60000)
        h.postDelayed(check, 120000)
    }

    // ================= 工具 =================

    /** 尺寸 -> 桌面组件 Provider */
    private fun providerForSize(size: String): Class<*> = when (size) {
        "1x1" -> FlowWidgetProvider1x1::class.java
        "1x2" -> FlowWidgetProvider1x2::class.java
        "2x1" -> FlowWidgetProvider2x1::class.java
        "2x2" -> FlowWidgetProvider2x2::class.java
        else -> FlowWidgetProvider4x2::class.java
    }

    private fun parseColorSafe(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: IllegalArgumentException) {
        Color.parseColor("#1E1E1E")
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
