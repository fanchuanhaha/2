package com.widgetflow.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.widgetflow.app.R
import com.widgetflow.app.databinding.ActivityMainBinding
import com.widgetflow.app.databinding.DialogImportBinding
import com.widgetflow.app.model.DataSource
import com.widgetflow.app.model.WidgetConfig
import com.widgetflow.app.model.resolveAliases
import com.widgetflow.app.net.ApiClient
import com.widgetflow.app.net.ApiResult
import com.widgetflow.app.storage.ConfigStore
import com.widgetflow.app.storage.SourceStore
import com.widgetflow.app.util.CrashLog
import com.widgetflow.app.widget.WidgetUpdater

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PICK_WIDGET_ID = "pickWidgetId"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var widgetAdapter: WidgetAdapter
    private var pickWidgetId = -1
    private var tabSource = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pickWidgetId = intent.getIntExtra(EXTRA_PICK_WIDGET_ID, -1)
        binding.pickBanner.isVisible = pickWidgetId >= 0
        // 桌面占位组件点按进入：直接切到「小部件」页选择
        if (pickWidgetId >= 0) tabSource = false

        sourceAdapter = SourceAdapter(
            onTest = { s -> testSource(s) },
            onEdit = { id ->
                startActivity(
                    Intent(this, SourceWizardActivity::class.java)
                        .putExtra(SourceWizardActivity.EXTRA_SOURCE_ID, id)
                )
            },
            onExport = { s -> exportSource(s) },
            onDelete = { s -> confirmDeleteSource(s) }
        )
        binding.sourceRecycler.layoutManager = LinearLayoutManager(this)
        binding.sourceRecycler.adapter = sourceAdapter

        widgetAdapter = WidgetAdapter(
            onEdit = { id ->
                startActivity(
                    Intent(this, WidgetEditorActivity::class.java)
                        .putExtra(WidgetEditorActivity.EXTRA_WIDGET_ID, id)
                )
            },
            onDelete = { w -> confirmDeleteWidget(w) },
            onExport = { w -> exportWidget(w) },
            onPick = if (pickWidgetId >= 0) ({ w -> assignWidget(w) }) else null
        )
        binding.widgetRecycler.layoutManager = LinearLayoutManager(this)
        binding.widgetRecycler.adapter = widgetAdapter

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                tabSource = tab.position == 0
                applyTab()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.fabNew.setOnClickListener {
            if (tabSource) {
                startActivity(Intent(this, SourceWizardActivity::class.java))
            } else {
                startActivity(Intent(this, WidgetEditorActivity::class.java))
            }
        }

        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_import -> {
                    showImportDialog()
                    true
                }
                R.id.menu_log -> {
                    showLogDialog()
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun applyTab() {
        val showSource = tabSource
        binding.sourceRecycler.isVisible = showSource
        binding.sourceEmpty.isVisible = showSource
        binding.widgetRecycler.isVisible = !showSource
        binding.widgetEmpty.isVisible = !showSource
    }

    private fun reload() {
        reloadSources()
        reloadWidgets()
        applyTab()
    }

    private fun reloadSources() {
        val list = SourceStore.all(this)
        sourceAdapter.submit(list)
        binding.sourceEmpty.isVisible = list.isEmpty()
        binding.sourceRecycler.isVisible = list.isNotEmpty()
        binding.toolbar.subtitle = "${list.size} 个数据源"
    }

    private fun reloadWidgets() {
        val list = ConfigStore.all(this)
        widgetAdapter.submit(list)
        binding.widgetEmpty.isVisible = list.isEmpty()
        binding.widgetRecycler.isVisible = list.isNotEmpty()
        val onDesk = list.count { it.widgetIds.isNotEmpty() }
        binding.toolbar.subtitle = "${list.size} 个小部件 · $onDesk 个在桌面"
    }

    // ================= 数据源操作 =================

    private fun testSource(s: DataSource) {
        val src = SourceStore.find(this, s.id) ?: return
        if (!ApiClient.isOnline(this)) {
            Toast.makeText(this, R.string.toast_offline, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "测试中…", Toast.LENGTH_SHORT).show()
        ApiClient.executeAsync(src, retries = 1) { result ->
            when (result) {
                is ApiResult.Success -> {
                    src.aliasMap = resolveAliases(src.rules, result.json, result.body)
                    src.lastUpdate = System.currentTimeMillis()
                    src.lastStatus = WidgetConfig.STATUS_OK
                    src.lastError = ""
                }
                is ApiResult.Failure -> {
                    src.lastStatus = WidgetConfig.STATUS_ERR
                    src.lastError = result.reason
                }
            }
            SourceStore.save(this, src)
            reloadSources()
            // 该数据源被引用的小部件同步重绘
            ConfigStore.all(this).forEach { w ->
                if (w.elements.any { it.sourceId == src.id }) {
                    w.widgetIds.forEach { WidgetUpdater.render(this, it) }
                }
            }
            Toast.makeText(
                this,
                when (result) {
                    is ApiResult.Success -> "测试成功"
                    is ApiResult.Failure -> "测试失败：${result.reason}"
                },
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun confirmDeleteSource(s: DataSource) {
        AlertDialog.Builder(this)
            .setTitle("删除数据源「${s.name}」")
            .setMessage("引用它的桌面小部件将显示为空，且不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                SourceStore.delete(this, s.id)
                WidgetUpdater.renderAll(this)
                Toast.makeText(
                    this, getString(R.string.toast_deleted, s.name), Toast.LENGTH_SHORT
                ).show()
                reload()
            }
            .show()
    }

    private fun exportSource(s: DataSource) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WidgetFlow source", s.toJson(export = true)))
        Toast.makeText(this, R.string.export_ok, Toast.LENGTH_SHORT).show()
    }

    // ================= 小部件操作 =================

    /** 桌面占位组件点按后进入：把所选小部件关联到该组件 */
    private fun assignWidget(widget: WidgetConfig) {
        widget.widgetIds.add(pickWidgetId)
        ConfigStore.save(this, widget)
        WidgetUpdater.updateNow(this, pickWidgetId)
        Toast.makeText(
            this,
            getString(R.string.toast_widget_assigned, widget.name),
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private fun confirmDeleteWidget(widget: WidgetConfig) {
        AlertDialog.Builder(this)
            .setTitle("删除「${widget.name}」")
            .setMessage("删除后其桌面组件将变为占位状态，且不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                ConfigStore.delete(this, widget.id)
                WidgetUpdater.renderAll(this)
                Toast.makeText(
                    this, getString(R.string.toast_deleted, widget.name), Toast.LENGTH_SHORT
                ).show()
                reload()
            }
            .show()
    }

    private fun exportWidget(widget: WidgetConfig) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WidgetFlow widget", ConfigStore.exportJson(widget)))
        Toast.makeText(this, R.string.export_ok, Toast.LENGTH_SHORT).show()
    }

    // ================= 导入 / 日志 =================

    private fun showImportDialog() {
        val dv = DialogImportBinding.inflate(LayoutInflater.from(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.import_dialog_title)
            .setView(dv.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ ->
                val text = dv.importText.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                importText(text)
            }
            .show()
    }

    /** 智能识别导入包类型：数据源包 or 小部件包 */
    private fun importText(text: String) {
        val src = SourceStore.import(this, text)
        if (src != null) {
            Toast.makeText(this, "数据源导入成功：${src.name}", Toast.LENGTH_SHORT).show()
            reload()
            return
        }
        val widget = ConfigStore.import(this, text)
        if (widget != null) {
            // 引用的数据源不存在时清空 sourceId，让用户在小部件编辑器中重新选择
            val ids = SourceStore.all(this).map { it.id }.toSet()
            widget.elements.forEach { el ->
                if (el.sourceId.isNotBlank() && !ids.contains(el.sourceId)) el.sourceId = ""
            }
            ConfigStore.save(this, widget)
            Toast.makeText(this, "小部件导入成功：${widget.name}", Toast.LENGTH_SHORT).show()
            reload()
            return
        }
        Toast.makeText(
            this,
            getString(R.string.import_fail, "JSON 无法解析或缺少必要字段"),
            Toast.LENGTH_LONG
        ).show()
    }

    /** 查看运行日志：支持复制 / 分享，便于排查闪退 */
    private fun showLogDialog() {
        val tv = TextView(this).apply {
            textSize = 11f
            setPadding(24, 16, 24, 16)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            text = CrashLog.readLog(this@MainActivity).ifBlank { "（暂无日志）" }
        }
        AlertDialog.Builder(this)
            .setTitle("运行日志")
            .setView(tv)
            .setNegativeButton("关闭", null)
            .setNeutralButton("清空") { _, _ ->
                CrashLog.logDir(this).let { d ->
                    d.listFiles()?.forEach { it.delete() }
                }
                Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("分享日志") { _, _ ->
                val log = CrashLog.readLog(this@MainActivity)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "WidgetFlow 运行日志")
                    putExtra(Intent.EXTRA_TEXT, log.ifBlank { "（暂无日志）" })
                }
                try {
                    startActivity(Intent.createChooser(send, "分享日志"))
                } catch (e: Exception) {
                    Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }
}
