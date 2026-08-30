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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.widgetflow.app.R
import com.widgetflow.app.databinding.ActivityWizardBinding
import com.widgetflow.app.databinding.ItemKvBinding
import com.widgetflow.app.databinding.ItemRuleBinding
import com.widgetflow.app.databinding.RowJsonNodeBinding
import com.widgetflow.app.model.Element
import com.widgetflow.app.model.ExtractRule
import com.widgetflow.app.model.WidgetConfig
import com.widgetflow.app.model.describe
import com.widgetflow.app.model.missingAliases
import com.widgetflow.app.model.regexFirst
import com.widgetflow.app.model.renderTemplate
import com.widgetflow.app.model.resolveAliases
import com.widgetflow.app.model.typeLabel
import com.widgetflow.app.net.ApiClient
import com.widgetflow.app.net.ApiResult
import com.widgetflow.app.net.JsonPath
import com.widgetflow.app.storage.ConfigStore
import com.widgetflow.app.util.CrashLog
import com.widgetflow.app.widget.FlowWidgetProvider2x2
import com.widgetflow.app.widget.FlowWidgetProvider4x2
import com.widgetflow.app.widget.PinResultReceiver
import com.widgetflow.app.widget.RefreshScheduler
import com.widgetflow.app.widget.WidgetUpdater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 四步配置向导：数据源 → 响应预览 → 字段抽取 → 组件编辑器。
 * 保存需通过测试（编辑已有配置时沿用其最近成功数据）。
 */
class WizardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONFIG_ID = "configId"
        private val TIMEOUTS = intArrayOf(5, 10, 20, 30)
        private val REFRESH_MINUTES = intArrayOf(30, 60, 360, WidgetConfig.FREQ_DAILY_8)
        // 深/浅色通用的可自定义色板（含白色与浅灰，便于深色背景下阅读）
        private val ELEMENT_COLORS = listOf(
            "#FFFFFF", "#E6E9F5", "#1F2430", "#4F63F5", "#14B8A6",
            "#C77E17", "#C63C3C", "#B85CFF", "#5D6A85", "#9AA3BC"
        )
        // 背景可选色板（含黑/白/深蓝/浅色等，适合搭配不同文字颜色）
        private val BG_COLORS = listOf(
            "#FFFFFF", "#F5F7FE", "#E6E9F5", "#000000", "#1F2430",
            "#232A55", "#4F63F5", "#14B8A6", "#C77E17", "#C63C3C"
        )
    }

    private lateinit var binding: ActivityWizardBinding
    private lateinit var draft: WidgetConfig
    private var editingExisting = false
    private var tested = false
    private var success: ApiResult.Success? = null
    private var suppress = true
    private var step = 1
    private var sel = -1
    /** 编辑模式：4 页同时显示为单页滚动，无需“下一步” */
    private var allVisible = false

    // 拖拽状态
    private var dragX = 0f
    private var dragY = 0f
    private var dragL = 0
    private var dragT = 0

    // JSON 树
    private val nodes = mutableListOf<TreeNode>()

    private class TreeNode(
        val key: String,
        val path: String,
        val value: Any?,
        val depth: Int,
        val row: View,
        val keyView: TextView,
        val children: MutableList<TreeNode> = mutableListOf(),
        var expanded: Boolean = true,
        var parent: TreeNode? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val configId = intent.getStringExtra(EXTRA_CONFIG_ID)
        editingExisting = configId != null
        draft = if (configId != null) {
            ConfigStore.find(this, configId) ?: WidgetConfig(id = WidgetConfig.newId())
        } else {
            WidgetConfig(id = WidgetConfig.newId())
        }
        tested = draft.aliasMap.isNotEmpty()

        setupHeader()
        bindStep1()
        setupStep3()
        setupStep4()
        suppress = false

        binding.step1.btnTest.setOnClickListener { runTest() }
        binding.step2.btnRetest.setOnClickListener { runTest() }
        binding.step1.presetHitokoto.setOnClickListener { applyHitokotoPreset() }
        binding.step1.presetText.setOnClickListener { applyTextPreset() }
        binding.step1.presetCustom.setOnClickListener { applyCustomPreset() }
        binding.step1.btnAddParam.setOnClickListener {
            addKvRow(binding.step1.boxParams, "", "")
            invalidateTest()
        }
        binding.step1.btnAddHeader.setOnClickListener {
            addKvRow(binding.step1.boxHeaders, "", "")
            invalidateTest()
        }

        binding.btnBack.setOnClickListener {
            if (step > 1) showStep(step - 1) else finish()
        }
        binding.btnPrev.setOnClickListener { showStep(step - 1) }
        binding.btnNext.setOnClickListener {
            if (step < 4 && !allVisible) showStep(step + 1) else save()
        }

        if (editingExisting) enterEditMode() else showStep(1)
    }

    // ================= 头部 =================

    private fun setupHeader() {
        binding.wzTitle.text =
            getString(if (editingExisting) R.string.wz_title_edit else R.string.wz_title_new)
        if (editingExisting && draft.widgetIds.isNotEmpty()) {
            binding.btnNext.text = getString(R.string.btn_save)
        }
    }

    private fun setDots(n: Int) {
        val on = getColor(R.color.accent)
        val off = getColor(R.color.rule)
        listOf(binding.dot0, binding.dot1, binding.dot2, binding.dot3).forEachIndexed { i, v ->
            v.setBackgroundColor(if (i < n) on else off)
        }
    }

    // ================= 步骤 1：数据源 =================

    private fun bindStep1() {
        binding.step1.inpName.setText(draft.name)
        binding.step1.inpUrl.setText(draft.url)
        binding.step1.inpBody.setText(draft.body)

        val methods = resources.getStringArray(R.array.methods)
        binding.step1.spinMethod.setSelection(methods.indexOf(draft.method).coerceAtLeast(0))
        binding.step1.spinTimeout.setSelection(
            TIMEOUTS.indexOf(draft.timeoutSec).coerceAtLeast(1)
        )
        updateBodyVisibility(draft.method)

        binding.step1.boxParams.removeAllViews()
        draft.params.forEach { addKvRow(binding.step1.boxParams, it.key, it.value) }
        binding.step1.boxHeaders.removeAllViews()
        draft.headers.forEach { addKvRow(binding.step1.boxHeaders, it.key, it.value) }

        binding.step1.spinMethod.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long
                ) {
                    val m = methods.getOrNull(pos) ?: return
                    if (m != draft.method) {
                        draft.method = m
                        updateBodyVisibility(m)
                        invalidateTest()
                    }
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }

        binding.step1.spinTimeout.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long
                ) {
                    val t = TIMEOUTS.getOrNull(pos) ?: return
                    if (t != draft.timeoutSec) {
                        draft.timeoutSec = t
                        invalidateTest()
                    }
                }

                override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
            }

        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!suppress) invalidateTest()
            }
        }
        binding.step1.inpName.addTextChangedListener(watcher)
        binding.step1.inpUrl.addTextChangedListener(watcher)
        binding.step1.inpBody.addTextChangedListener(watcher)
    }

    private fun updateBodyVisibility(method: String) {
        val show = method == "POST"
        binding.step1.labelBody.isVisible = show
        binding.step1.inpBody.isVisible = show
    }

    private fun addKvRow(box: LinearLayout, key: String, value: String) {
        val row = ItemKvBinding.inflate(layoutInflater, box, false)
        row.kvKey.setText(key)
        row.kvValue.setText(value)
        row.kvDelete.setOnClickListener {
            box.removeView(row.root)
            invalidateTest()
        }
        val watcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (!suppress) invalidateTest()
            }
        }
        row.kvKey.addTextChangedListener(watcher)
        row.kvValue.addTextChangedListener(watcher)
        box.addView(row.root)
    }

    private fun collectKV(box: LinearLayout): MutableList<com.widgetflow.app.model.KeyValue> {
        val out = mutableListOf<com.widgetflow.app.model.KeyValue>()
        for (i in 0 until box.childCount) {
            val child = box.getChildAt(i)
            val k = child.findViewById<EditText>(R.id.kv_key)?.text?.toString()?.trim() ?: ""
            val v = child.findViewById<EditText>(R.id.kv_value)?.text?.toString()?.trim() ?: ""
            if (k.isNotEmpty()) out.add(com.widgetflow.app.model.KeyValue(k, v))
        }
        return out
    }

    private fun readStep1() {
        draft.name = binding.step1.inpName.text.toString().trim()
        draft.url = binding.step1.inpUrl.text.toString().trim()
        draft.body = binding.step1.inpBody.text.toString()
        draft.params = collectKV(binding.step1.boxParams)
        draft.headers = collectKV(binding.step1.boxHeaders)
    }

    private fun applyHitokotoPreset() {
        suppress = true
        binding.step1.inpName.setText(getString(R.string.preset_hitokoto))
        binding.step1.inpUrl.setText("https://v1.hitokoto.cn/")
        binding.step1.boxParams.removeAllViews()
        addKvRow(binding.step1.boxParams, "c", "f")
        addKvRow(binding.step1.boxParams, "encode", "json")
        suppress = false
        invalidateTest()
    }

    /** 纯文本一键接口：返回 text/plain，用整段/正则抽取 */
    private fun applyTextPreset() {
        suppress = true
        binding.step1.inpName.setText("纯文本 · 一言")
        binding.step1.inpUrl.setText("https://yy.802213.xyz/api/")
        binding.step1.boxParams.removeAllViews()
        suppress = false
        invalidateTest()
    }

    private fun applyCustomPreset() {
        suppress = true
        binding.step1.inpName.setText("")
        binding.step1.inpUrl.setText("")
        binding.step1.boxParams.removeAllViews()
        suppress = false
        invalidateTest()
    }

    private fun invalidateTest() {
        tested = false
        success = null
        setPill(R.string.status_modified, R.color.muted, android.graphics.Color.TRANSPARENT)
        updateBottomBar()
    }

    private fun setPill(textRes: Int, colorRes: Int, bgColor: Int) {
        binding.step1.statusPill.text = getString(textRes)
        binding.step1.statusPill.setTextColor(getColor(colorRes))
        binding.step1.statusPill.setBackgroundColor(bgColor)
    }

    private fun setPill(text: String, colorRes: Int, bgColor: Int) {
        binding.step1.statusPill.text = text
        binding.step1.statusPill.setTextColor(getColor(colorRes))
        binding.step1.statusPill.setBackgroundColor(bgColor)
    }

    private fun runTest() {
        readStep1()
        if (draft.url.isBlank()) {
            Toast.makeText(this, "请先填写请求 URL", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ApiClient.isOnline(this)) {
            Toast.makeText(this, R.string.toast_offline, Toast.LENGTH_SHORT).show()
            return
        }
        binding.step1.btnTest.isEnabled = false
        setPill(R.string.status_testing, R.color.accent, getColor(R.color.accent_light))
        CrashLog.write(this, "test", "start url=${draft.url} method=${draft.method}")
        ApiClient.executeAsync(draft) { result ->
            binding.step1.btnTest.isEnabled = true
            when (result) {
                is ApiResult.Success -> {
                    success = result
                    tested = true
                    CrashLog.write(
                        this, "test", "ok ${result.ms}ms ${result.body.length}B json=${result.json != null}"
                    )
                    setPill(
                        "200 · ${result.ms}ms · ${result.body.length}B",
                        R.color.ok, getColor(R.color.ok_light)
                    )
                    // 编辑模式单页：测试成功后实时刷新预览/抽取/编辑器
                    if (allVisible) {
                        renderStep2()
                        renderStep3()
                        renderEditor()
                    }
                }
                is ApiResult.Failure -> {
                    tested = false
                    CrashLog.write(this, "test", "fail ${result.reason} ${result.detail}")
                    setPill(
                        "失败 · ${result.reason}",
                        R.color.err, getColor(R.color.err_light)
                    )
                }
            }
            updateBottomBar()
        }
    }

    // ================= 步骤 2：响应预览 =================

    private fun renderStep2() {
        val s = success
        if (s == null) {
            binding.step2.previewPill.text = "尚未测试"
            binding.step2.previewMeta.text = ""
            binding.step2.tvJson.text = ""
            if (editingExisting) {
                // 编辑模式不自动联网测试，沿用上次成功数据
                binding.step2.previewPill.text = "沿用上次数据"
                binding.step2.previewMeta.text = "如需最新数据请点上方「测试」"
                binding.step2.tvJson.text =
                    "（沿用最近一次成功数据）\n" +
                        draft.aliasMap.entries.joinToString("\n") { "${it.key} = ${it.value}" }
            } else {
                runTest()
            }
            return
        }
        binding.step2.previewPill.text = "200 OK"
        binding.step2.previewPill.setTextColor(getColor(R.color.ok))
        binding.step2.previewPill.setBackgroundColor(getColor(R.color.ok_light))
        val kind = if (s.json != null) "JSON" else "文本"
        binding.step2.previewMeta.text = "${s.ms}ms · ${s.body.length}B · $kind"
        binding.step2.tvJson.text = if (s.json != null) JsonPath.pretty(s.json) else s.body
    }

    // ================= 步骤 3：字段抽取 =================

    private fun setupStep3() {
        // 正则实时预览
        binding.step3.inpRegex.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateRegexPreview()
            }
        })
        binding.step3.btnAddRegex.setOnClickListener {
            addRegexRule()
        }
        binding.step3.btnAddText.setOnClickListener {
            addTextRule()
        }
    }

    /** 添加正则规则 */
    private fun addRegexRule() {
        val body = success?.body ?: ""
        if (body.isBlank()) {
            Toast.makeText(this, "暂无响应文本，请先测试", Toast.LENGTH_SHORT).show()
            return
        }
        if (draft.rules.size >= WidgetConfig.MAX_RULES) {
            Toast.makeText(this, "规则数量已达上限 ${WidgetConfig.MAX_RULES} 条", Toast.LENGTH_SHORT).show()
            return
        }
        val pattern = binding.step3.inpRegex.text.toString().trim()
        if (pattern.isBlank()) {
            Toast.makeText(this, "请先填写正则表达式", Toast.LENGTH_SHORT).show()
            return
        }
        var alias = binding.step3.inpRegexAlias.text.toString().trim()
            .replace(Regex("\\W"), "_").ifBlank { "match" }
        val base = alias
        var n = 2
        while (draft.rules.any { it.alias == alias }) {
            alias = "${base}_$n"; n++
        }
        draft.rules.add(ExtractRule(pattern, alias, "regex"))
        binding.step3.inpRegexAlias.setText("")
        renderRules()
        updateBottomBar()
    }

    /** 添加整段文本规则 */
    private fun addTextRule() {
        val body = success?.body ?: ""
        if (body.isBlank()) {
            Toast.makeText(this, "暂无响应文本，请先测试", Toast.LENGTH_SHORT).show()
            return
        }
        if (draft.rules.size >= WidgetConfig.MAX_RULES) {
            Toast.makeText(this, "规则数量已达上限 ${WidgetConfig.MAX_RULES} 条", Toast.LENGTH_SHORT).show()
            return
        }
        if (draft.rules.any { it.type == "text" }) {
            Toast.makeText(this, "整段文本规则已存在", Toast.LENGTH_SHORT).show()
            return
        }
        var alias = "text"
        var n = 2
        while (draft.rules.any { it.alias == alias }) {
            alias = "text_$n"; n++
        }
        draft.rules.add(ExtractRule("*text*", alias, "text"))
        renderRules()
        updateBottomBar()
    }

    private fun updateRegexPreview() {
        val body = success?.body ?: ""
        val pattern = binding.step3.inpRegex.text.toString().trim()
        if (pattern.isBlank()) {
            binding.step3.regexPreview.text = ""
            return
        }
        val m = regexFirst(body, pattern)
        binding.step3.regexPreview.text =
            if (m.isEmpty()) "（无匹配）" else "匹配结果: $m"
    }

    private fun renderStep3() {
        val s = success
        val isText = s != null && s.json == null
        binding.step3.jsonMode.isVisible = !isText
        binding.step3.textMode.isVisible = isText

        if (isText) {
            binding.step3.tvTextPreview.text = s?.body ?: ""
            updateRegexPreview()
        } else {
            binding.step3.boxTree.removeAllViews()
            nodes.clear()
            s?.let { ss -> buildNode(ss.json, "$", "$", 0, null) }
            refreshTreeVisibility()
        }
        renderRules()
        if (s == null) {
            binding.step3.rulesHint.text =
                "未获取到新响应，下方规则沿用最近一次成功数据编辑"
        }
    }

    private fun buildNode(value: Any?, key: String, path: String, depth: Int, parent: TreeNode?) {
        val row = RowJsonNodeBinding.inflate(
            layoutInflater, binding.step3.boxTree, false
        )
        row.nodeIndent.text = "\u00A0\u00A0".repeat(depth)
        row.nodeKey.text = "$key:"

        val isContainer = value is org.json.JSONObject || value is org.json.JSONArray
        val preview = when {
            value == null -> "null"
            value is org.json.JSONObject -> "Object(${value.length()})"
            value is org.json.JSONArray -> "Array(${value.length()})"
            value is String -> "\"" + value.take(48) + "\""
            else -> value.toString()
        }
        row.nodeValue.text = preview
        row.nodeValue.setTextColor(
            getColor(if (value is String) R.color.ok else R.color.warn)
        )
        row.nodeToggle.isVisible = isContainer

        val node = TreeNode(key, path, value, depth, row.root, row.nodeKey)
        node.parent = parent
        parent?.children?.add(node)
        nodes.add(node)
        binding.step3.boxTree.addView(row.root)

        if (isContainer) {
            row.nodeToggle.setOnClickListener {
                node.expanded = !node.expanded
                row.nodeToggle.text = if (node.expanded) "▾" else "▸"
                refreshTreeVisibility()
            }
        }
        row.nodeKey.setOnClickListener { pickPath(node) }

        when (value) {
            is org.json.JSONObject -> value.keys().forEach { k ->
                buildNode(value.opt(k), k, "$path.$k", depth + 1, node)
            }
            is org.json.JSONArray -> for (i in 0 until value.length()) {
                buildNode(value.opt(i), "[$i]", "$path[$i]", depth + 1, node)
            }
        }
    }

    private fun refreshTreeVisibility() {
        nodes.forEach { n ->
            n.row.isVisible = n.parent == null ||
                (n.parent!!.row.isVisible && n.parent!!.expanded)
        }
    }

    private fun pickPath(node: TreeNode) {
        if (node.path == "$") return
        // 高亮
        nodes.forEach { it.keyView.setBackgroundColor(android.graphics.Color.TRANSPARENT) }
        node.keyView.setBackgroundColor(0x3314B8A6)

        if (draft.rules.any { it.path == node.path }) {
            binding.step3.rulesHint.text = "路径 ${node.path} 已在规则中"
            return
        }
        if (draft.rules.size >= WidgetConfig.MAX_RULES) {
            binding.step3.rulesHint.text = "规则数量已达上限 ${WidgetConfig.MAX_RULES} 条"
            return
        }
        var alias = node.key.replace(Regex("\\W"), "_")
        if (alias.isBlank() || alias.startsWith("[")) alias = "field"
        var base = alias
        var n = 2
        while (draft.rules.any { it.alias == alias }) {
            alias = "${base}_$n"
            n++
        }
        draft.rules.add(com.widgetflow.app.model.ExtractRule(node.path, alias))
        renderRules()
        binding.step3.rulesHint.text = "新增 ${node.path} → {$alias}，模板中以 {$alias} 引用"
        updateBottomBar()
    }

    private fun renderRules() {
        val box = binding.step3.boxRules
        box.removeAllViews()
        suppress = true
        draft.rules.forEachIndexed { i, rule ->
            val row = ItemRuleBinding.inflate(layoutInflater, box, false)
            row.rulePath.text = "[${rule.typeLabel()}] ${rule.describe()}"

            val s = success
            val sample = when {
                s == null -> (draft.aliasMap[rule.alias] ?: "").ifBlank { "（沿用旧数据）" }
                rule.type == "text" -> s.body.take(24)
                rule.type == "regex" ->
                    regexFirst(s.body, rule.path).take(24).ifBlank { "（无匹配）" }
                else -> {
                    val v = JsonPath.get(s.json, rule.path)
                    if (v == null) "（无匹配）" else JsonPath.valueToString(v).take(24)
                }
            }
            row.ruleSample.text = "示例: $sample"

            row.ruleAlias.setText(rule.alias)
            row.ruleAlias.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (!suppress && i < draft.rules.size) {
                        draft.rules[i].alias =
                            s?.toString()?.trim()?.replace(Regex("\\W"), "_")?.ifBlank { "field" }
                                ?: "field"
                    }
                }
            })
            row.ruleDelete.setOnClickListener {
                draft.rules.removeAt(i)
                renderRules()
                updateBottomBar()
            }
            box.addView(row.root)
        }
        suppress = false
        if (draft.rules.isEmpty()) {
            binding.step3.rulesHint.text = "还没有规则，点击上方 JSON 里的字段名试试"
        }
        updateBottomBar()
    }

    // ================= 步骤 4：组件编辑器 =================

    private fun setupStep4() {
        // 字号 9..18 → progress 0..9
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

        binding.step4.btnSize4x2.setOnClickListener { setSize("4x2") }
        binding.step4.btnSize2x2.setOnClickListener { setSize("2x2") }

        binding.step4.btnAddElement.setOnClickListener {
            if (draft.elements.size >= WidgetConfig.MAX_ELEMENTS) {
                Toast.makeText(this, "每组件最多 ${WidgetConfig.MAX_ELEMENTS} 个元素", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val alias = draft.rules.getOrNull(draft.elements.size % draft.rules.size.coerceAtLeast(1))?.alias
            draft.elements.add(
                Element(
                    template = alias?.let { "{$it}" } ?: "新文本",
                    fontSize = 10,
                    color = if (isDarkMode()) "#E6E9F5" else "#1F2430",
                    x = 30f, y = 50f
                )
            )
            sel = draft.elements.size - 1
            renderEditor()
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

        // 重置为自适应大小（清除画布拖拽设置的显式宽高）
        binding.step4.btnResetSize.setOnClickListener {
            if (sel in draft.elements.indices) {
                draft.elements[sel].width = 0f
                draft.elements[sel].height = 0f
                rebuildCanvas()
                updatePanel()
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

        // 背景颜色色板（含默认“无”留空项 + 深浅色可选底色）
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

    /** 构建背景颜色色板：点选写入 draft.bgColor 并同步画布预览 */
    private fun buildBgColorSwatches() {
        binding.step4.boxBgColors.removeAllViews()
        BG_COLORS.forEach { hex ->
            val wrap = FrameLayout(this)
            val wlp = LinearLayout.LayoutParams(36.dp(), 36.dp())
            wlp.rightMargin = 8.dp()
            wrap.layoutParams = wlp
            // 选中时显示外圈描边
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
        if (draft.size == "4x2") {
            binding.step4.btnSize4x2.setTextColor(active)
            binding.step4.btnSize4x2.setBackgroundColor(activeBg)
            binding.step4.btnSize2x2.setTextColor(inactive)
            binding.step4.btnSize2x2.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            binding.step4.btnSize2x2.setTextColor(active)
            binding.step4.btnSize2x2.setBackgroundColor(activeBg)
            binding.step4.btnSize4x2.setTextColor(inactive)
            binding.step4.btnSize4x2.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    private fun applyCanvasWidth() {
        val canvas = binding.step4.canvas
        canvas.post {
            val wrap = canvas.parent as? FrameLayout ?: return@post
            canvas.layoutParams.width =
                if (draft.size == "2x2") wrap.width / 2 else ViewGroup.LayoutParams.MATCH_PARENT
            canvas.requestLayout()
        }
    }

    private fun previewMap(): Map<String, String> =
        success?.let { resolveAliases(draft.rules, it.json, it.body) } ?: draft.aliasMap

    private fun timeNow(): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun buildDefaultElements() {
        val dark = isDarkMode()
        val ink = if (dark) "#E6E9F5" else "#1F2430"
        val muted = if (dark) "#9AA3BC" else "#5D6A85"
        draft.elements.clear()
        draft.rules.getOrNull(0)?.let {
            draft.elements.add(Element("『{${it.alias}}』", 13, ink, 6f, 12f))
        }
        draft.rules.getOrNull(1)?.let {
            draft.elements.add(Element("—— {${it.alias}}", 9, muted, 6f, 64f))
        }
        draft.elements.add(Element("{time}", 7, "#9AA3BC", 70f, 87f))
    }

    private fun renderEditor() {
        if (draft.elements.isEmpty()) buildDefaultElements()
        if (sel !in draft.elements.indices) sel = 0
        updateSizeButtons()
        applyCanvasWidth()
        rebuildCanvas()
        renderChips()
        updatePanel()
    }

    private fun rebuildCanvas() {
        val canvas = binding.step4.canvas
        canvas.removeAllViews()
        // 预览画布应用所选背景色（空 = 默认主题圆角背景）
        if (draft.bgColor.isNotBlank()) {
            canvas.background = null
            canvas.setBackgroundColor(parseColorSafe(draft.bgColor))
        } else {
            canvas.background = getDrawable(R.drawable.widget_bg)
        }
        val map = previewMap()
        val time = timeNow()
        draft.elements.forEachIndexed { i, el ->
            // 容器：承载文本 TextView + 右下角缩放柄
            val box = FrameLayout(this)
            box.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            box.tag = i

            val tv = TextView(this)
            tv.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            tv.text = renderTemplate(el.template, map, time)
            tv.textSize = el.fontSize.toFloat()
            tv.setTextColor(parseColorSafe(el.color))
            tv.maxLines = 4
            if (i == sel) tv.setBackgroundColor(0x1A4F63F5)
            box.addView(tv)

            // 缩放柄：选中时显示，拖拽可自定义宽高
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
            // 显式宽高（0=自适应内容）
            val tvLp = tv.layoutParams as FrameLayout.LayoutParams
            tvLp.width = if (el.width > 0f) (el.width / 100f * canvas.width).toInt().coerceAtLeast(20)
            else ViewGroup.LayoutParams.WRAP_CONTENT
            tvLp.height = if (el.height > 0f) (el.height / 100f * canvas.height).toInt().coerceAtLeast(20)
            else ViewGroup.LayoutParams.WRAP_CONTENT
            tv.requestLayout()
            box.requestLayout()
        }
    }

    /** 拖拽移动元素（容器整体） */
    private fun attachDrag(box: View, el: Element, index: Int) {
        box.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    selectElement(index, rebuild = false)
                    dragX = event.rawX
                    dragY = event.rawY
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    dragL = lp.leftMargin
                    dragT = lp.topMargin
                    true
                }
                MotionEvent.ACTION_MOVE -> {
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
                    val w = binding.step4.canvas.width
                    val h = binding.step4.canvas.height
                    if (w > 0 && h > 0) {
                        val lp = v.layoutParams as FrameLayout.LayoutParams
                        el.x = lp.leftMargin * 100f / w
                        el.y = lp.topMargin * 100f / h
                    }
                    updatePanel()
                    v.performClick()
                    true
                }
                else -> false
            }
        }
    }

    /** 拖拽右下角缩放柄：自定义元素宽高（百分比，0=自适应） */
    private fun attachResize(handle: View, tv: TextView, el: Element) {
        var sx = 0f
        var sy = 0f
        var startW = 0
        var startH = 0
        handle.setOnTouchListener { v, event ->
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

    /** 更新选中高亮与缩放柄可见性（不重建画布，避免打断拖拽） */
    private fun updateHandles() {
        val canvas = binding.step4.canvas
        for (i in 0 until canvas.childCount) {
            val box = canvas.getChildAt(i) as? FrameLayout ?: continue
            val tag = box.tag as? Int ?: continue
            (box.getChildAt(0) as? TextView)?.let { tv ->
                tv.setBackgroundColor(if (tag == sel) 0x1A4F63F5 else Color.TRANSPARENT)
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
            // 注意：{} 两个大括号都必须转义（Android ICU 正则对未转义的 } 会报 PatternSyntaxException）
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
        updatePanel()
        if (rebuild) rebuildCanvas() else updateHandles()
    }

    private fun updatePanel() {
        val has = sel in draft.elements.indices
        val el = draft.elements.getOrNull(sel)
        binding.step4.inpTemplate.isEnabled = has
        binding.step4.seekFont.isEnabled = has
        binding.step4.btnDeleteElement.isEnabled = has
        binding.step4.propName.text = if (has && el != null) {
            val sizeTxt = if (el.hasExplicitSize()) {
                " · 尺寸 ${el.width.toInt()}% x ${el.height.toInt()}%"
            } else {
                " · 自适应"
            }
            "元素 ${sel + 1} · 位置 ${el.x.toInt()}%, ${el.y.toInt()}%$sizeTxt"
        } else {
            "未选中元素 · 点击画布或上方标签选择"
        }
        binding.step4.propAliases.text =
            "可用: " + (draft.rules.map { "{${it.alias}}" } + "{time}").joinToString(" ")
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
    }

    private fun updatePanelHeadOnly() {
        updatePanel()
    }

    // ================= 步骤切换 =================

    private fun isDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    /** 编辑模式：4 页同时显示为单页滚动，已有信息就地修改 */
    private fun enterEditMode() {
        allVisible = true
        CrashLog.write(this, "wizard", "editMode all sections visible")
        binding.step1.root.isVisible = true
        binding.step2.root.isVisible = true
        binding.step3.root.isVisible = true
        binding.step4.root.isVisible = true
        binding.secHead1.isVisible = true
        binding.secHead2.isVisible = true
        binding.secHead3.isVisible = true
        binding.secHead4.isVisible = true
        binding.stepDots.isVisible = false
        binding.wzSubtitle.text = "编辑模式 · 向下滚动修改全部设置"
        renderStep2()
        renderStep3()
        renderEditor()
        updateBottomBar()
        binding.stepScroll.fullScroll(View.FOCUS_UP)
    }

    private fun showStep(n: Int) {
        allVisible = false
        step = n.coerceIn(1, 4)
        CrashLog.write(this, "wizard", "showStep=$step tested=$tested rules=${draft.rules.size}")
        binding.step1.root.isVisible = step == 1
        binding.step2.root.isVisible = step == 2
        binding.step3.root.isVisible = step == 3
        binding.step4.root.isVisible = step == 4
        binding.secHead1.isVisible = false
        binding.secHead2.isVisible = false
        binding.secHead3.isVisible = false
        binding.secHead4.isVisible = false
        binding.stepDots.isVisible = true
        setDots(step)

        binding.wzSubtitle.text = getString(
            when (step) {
                1 -> R.string.wz_step1_subtitle
                2 -> R.string.wz_step2_subtitle
                3 -> R.string.wz_step3_subtitle
                else -> R.string.wz_step4_subtitle
            }
        )

        when (step) {
            2 -> renderStep2()
            3 -> renderStep3()
            4 -> renderEditor()
        }
        updateBottomBar()
        binding.stepScroll.fullScroll(View.FOCUS_UP)
    }

    private fun updateBottomBar() {
        if (allVisible) {
            // 编辑模式：单页滚动，底部只保留“保存”
            binding.btnPrev.isVisible = false
            binding.btnNext.isVisible = true
            binding.btnNext.isEnabled = true
            binding.btnNext.text = getString(R.string.btn_save)
            return
        }
        val nextEnabled = when (step) {
            1 -> tested
            2 -> true
            3 -> draft.rules.isNotEmpty()
            else -> true
        }
        binding.btnNext.isEnabled = nextEnabled
        binding.btnPrev.isVisible = step > 1
        if (step == 4) {
            binding.btnNext.text = getString(
                if (editingExisting && draft.widgetIds.isNotEmpty()) R.string.btn_save
                else R.string.btn_save
            )
        } else {
            binding.btnNext.text = getString(R.string.btn_next)
        }
        if (step == 1 && !tested) {
            setPill(
                if (draft.url.isBlank() && draft.name.isBlank()) R.string.status_need_test
                else R.string.status_modified,
                R.color.muted, android.graphics.Color.TRANSPARENT
            )
        }
    }

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
        readStep1()
        if (draft.name.isBlank()) draft.name = "未命名配置"
        if (!tested && draft.aliasMap.isEmpty()) {
            Toast.makeText(this, R.string.toast_need_test, Toast.LENGTH_SHORT).show()
            if (!allVisible) showStep(1)
            return
        }
        if (draft.rules.isEmpty()) {
            Toast.makeText(this, R.string.toast_need_rule, Toast.LENGTH_SHORT).show()
            if (!allVisible) showStep(3)
            return
        }
        if (draft.elements.isEmpty()) {
            Toast.makeText(this, R.string.toast_need_element, Toast.LENGTH_SHORT).show()
            return
        }
        // 模板别名检查（仅提示，不阻断）
        val aliases = draft.rules.map { it.alias }.toSet()
        draft.elements.forEach { el ->
            missingAliases(el.template, aliases).forEach { m ->
                Toast.makeText(this, getString(R.string.toast_alias_missing, m), Toast.LENGTH_SHORT).show()
            }
        }

        success?.let { s ->
            draft.aliasMap = resolveAliases(draft.rules, s.json, s.body)
            draft.lastUpdate = System.currentTimeMillis()
            draft.lastStatus = WidgetConfig.STATUS_OK
            draft.lastError = ""
        }
        ConfigStore.save(this, draft)
        RefreshScheduler.ensureScheduled(this)
        CrashLog.write(
            this, "save",
            "name=${draft.name} url=${draft.url} rules=${draft.rules.size} " +
                "elements=${draft.elements.size} size=${draft.size} widgetIds=${draft.widgetIds.size}"
        )

        if (draft.widgetIds.isNotEmpty()) {
            draft.widgetIds.forEach { WidgetUpdater.render(this, it) }
            Toast.makeText(this, getString(R.string.toast_saved, draft.name), Toast.LENGTH_SHORT).show()
            finish()
        } else {
            requestPinWidget()
        }
    }

    /** 保存新配置后请求固定到桌面（API 26+，需启动器支持） */
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
        val provider = ComponentName(
            this,
            if (draft.size == "2x2") FlowWidgetProvider2x2::class.java
            else FlowWidgetProvider4x2::class.java
        )
        if (awm.isRequestPinAppWidgetSupported) {
            val before = awm.getAppWidgetIds(provider).toSet()
            val callback = PendingIntent.getBroadcast(
                this, 40001 + (draft.id.hashCode() and 0xFFFF),
                Intent(this, PinResultReceiver::class.java).putExtra("configId", draft.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val ok = awm.requestPinAppWidget(provider, null, callback)
            CrashLog.write(this, "requestPin", "supported=true ok=$ok size=${draft.size}")
            // 部分启动器（如华为 EMUI）回调里 appWidgetId 会返回 -1，
            // 这里轮询新出现的组件 ID 并自动关联到当前配置
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
        // 多轮探测：用户在桌面确认放置可能耗时（华为等启动器确认较慢）
        val h = Handler(Looper.getMainLooper())
        h.postDelayed(check, 6000)
        h.postDelayed(check, 15000)
        h.postDelayed(check, 30000)
        h.postDelayed(check, 60000)
        h.postDelayed(check, 120000)
    }

    // ================= 工具 =================

    private fun parseColorSafe(hex: String): Int = try {
        Color.parseColor(hex)
    } catch (e: IllegalArgumentException) {
        Color.parseColor("#1F2430")
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
}
