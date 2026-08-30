package com.widgetflow.app.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.widgetflow.app.R
import com.widgetflow.app.databinding.ActivityWizardBinding
import com.widgetflow.app.databinding.ItemKvBinding
import com.widgetflow.app.databinding.ItemRuleBinding
import com.widgetflow.app.databinding.RowJsonNodeBinding
import com.widgetflow.app.model.DataSource
import com.widgetflow.app.model.ExtractRule
import com.widgetflow.app.model.WidgetConfig
import com.widgetflow.app.model.describe
import com.widgetflow.app.model.regexFirst
import com.widgetflow.app.model.resolveAliases
import com.widgetflow.app.model.typeLabel
import com.widgetflow.app.net.ApiClient
import com.widgetflow.app.net.ApiResult
import com.widgetflow.app.net.JsonPath
import com.widgetflow.app.storage.SourceStore
import com.widgetflow.app.util.CrashLog

/**
 * 数据源向导：请求定义 → 响应预览 → 字段抽取。
 * 保存到 SourceStore，可被多个小部件复用。
 * 编辑已有数据源时为单页滚动，全部设置就地修改。
 */
class SourceWizardActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_ID = "sourceId"
        private val TIMEOUTS = intArrayOf(5, 10, 20, 30)
    }

    private lateinit var binding: ActivityWizardBinding
    private lateinit var draft: DataSource
    private var editingExisting = false
    private var tested = false
    private var success: ApiResult.Success? = null
    private var suppress = true
    private var step = 1
    /** 编辑模式：3 页同时显示为单页滚动，无需“下一步” */
    private var allVisible = false

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

        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID)
        editingExisting = sourceId != null
        draft = if (sourceId != null) {
            SourceStore.find(this, sourceId) ?: DataSource(id = WidgetConfig.newId())
        } else {
            DataSource(id = WidgetConfig.newId())
        }
        tested = draft.aliasMap.isNotEmpty()

        setupHeader()
        bindStep1()
        setupStep3()
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
            if (step < 3 && !allVisible) showStep(step + 1) else save()
        }

        if (editingExisting) enterEditMode() else showStep(1)
    }

    // ================= 头部 =================

    private fun setupHeader() {
        binding.wzTitle.text =
            getString(if (editingExisting) R.string.wz_title_edit else R.string.wz_title_new)
        binding.stepDots.visibility = View.VISIBLE
    }

    private fun setDots(n: Int) {
        val on = getColor(R.color.accent)
        val off = getColor(R.color.rule)
        listOf(binding.dot0, binding.dot1, binding.dot2).forEachIndexed { i, v ->
            v.setBackgroundColor(if (i < n) on else off)
        }
        binding.dot3.isVisible = false
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
                    if (allVisible) {
                        renderStep2()
                        renderStep3()
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
        binding.step3.inpRegex.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                updateRegexPreview()
            }
        })
        binding.step3.btnAddRegex.setOnClickListener { addRegexRule() }
        binding.step3.btnAddText.setOnClickListener { addTextRule() }
    }

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

    // ================= 步骤切换 =================

    /** 编辑模式：3 页同时显示为单页滚动，已有信息就地修改 */
    private fun enterEditMode() {
        allVisible = true
        CrashLog.write(this, "wizard", "editMode all sections visible")
        binding.step1.root.isVisible = true
        binding.step2.root.isVisible = true
        binding.step3.root.isVisible = true
        binding.secHead1.isVisible = true
        binding.secHead2.isVisible = true
        binding.secHead3.isVisible = true
        binding.secHead4.isVisible = false
        binding.step4.root.isVisible = false
        binding.stepDots.isVisible = false
        binding.wzSubtitle.text = getString(R.string.wz_subtitle_edit_source)
        renderStep2()
        renderStep3()
        updateBottomBar()
        binding.stepScroll.fullScroll(View.FOCUS_UP)
    }

    private fun showStep(n: Int) {
        allVisible = false
        step = n.coerceIn(1, 3)
        CrashLog.write(this, "wizard", "showStep=$step tested=$tested rules=${draft.rules.size}")
        binding.step1.root.isVisible = step == 1
        binding.step2.root.isVisible = step == 2
        binding.step3.root.isVisible = step == 3
        binding.step4.root.isVisible = false
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
                else -> R.string.wz_step3_subtitle
            }
        )

        when (step) {
            2 -> renderStep2()
            3 -> renderStep3()
        }
        updateBottomBar()
        binding.stepScroll.fullScroll(View.FOCUS_UP)
    }

    private fun updateBottomBar() {
        if (allVisible) {
            binding.btnPrev.isVisible = false
            binding.btnNext.isVisible = true
            binding.btnNext.isEnabled = true
            binding.btnNext.text = getString(R.string.btn_save)
            return
        }
        val nextEnabled = when (step) {
            1 -> tested
            2 -> true
            else -> draft.rules.isNotEmpty()
        }
        binding.btnNext.isEnabled = nextEnabled
        binding.btnPrev.isVisible = step > 1
        binding.btnNext.text = getString(
            if (step == 3) R.string.btn_save else R.string.btn_next
        )
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
        if (draft.name.isBlank()) draft.name = "未命名数据源"
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
        success?.let { s ->
            draft.aliasMap = resolveAliases(draft.rules, s.json, s.body)
            draft.lastUpdate = System.currentTimeMillis()
            draft.lastStatus = WidgetConfig.STATUS_OK
            draft.lastError = ""
        }
        SourceStore.save(this, draft)
        CrashLog.write(
            this, "save",
            "source name=${draft.name} url=${draft.url} rules=${draft.rules.size}"
        )
        Toast.makeText(
            this, getString(R.string.toast_saved, draft.name), Toast.LENGTH_SHORT
        ).show()
        finish()
    }
}
