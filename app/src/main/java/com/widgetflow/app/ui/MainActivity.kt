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
import com.widgetflow.app.R
import com.widgetflow.app.databinding.ActivityMainBinding
import com.widgetflow.app.databinding.DialogImportBinding
import com.widgetflow.app.model.WidgetConfig
import com.widgetflow.app.storage.ConfigStore
import com.widgetflow.app.util.CrashLog
import com.widgetflow.app.widget.PinResultReceiver
import com.widgetflow.app.widget.WidgetUpdater

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PICK_WIDGET_ID = "pickWidgetId"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: ConfigAdapter
    private var pickWidgetId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pickWidgetId = intent.getIntExtra(EXTRA_PICK_WIDGET_ID, -1)
        binding.pickBanner.isVisible = pickWidgetId >= 0

        adapter = ConfigAdapter(
            onEdit = { id ->
                startActivity(
                    Intent(this, WizardActivity::class.java)
                        .putExtra(WizardActivity.EXTRA_CONFIG_ID, id)
                )
            },
            onDelete = { config -> confirmDelete(config) },
            onExport = { config -> exportConfig(config) },
            onPick = if (pickWidgetId >= 0) ({ config -> assignWidget(config) }) else null
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.fabNew.setOnClickListener {
            startActivity(Intent(this, WizardActivity::class.java))
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

    private fun reload() {
        val list = ConfigStore.all(this)
        adapter.submit(list)
        binding.emptyView.isVisible = list.isEmpty()
        binding.recycler.isVisible = list.isNotEmpty()
        val onDesk = list.count { it.widgetIds.isNotEmpty() }
        binding.toolbar.subtitle = "${list.size} 个配置 · $onDesk 个在桌面"
    }

    /** 桌面占位组件点按后进入：把所选配置关联到该组件 */
    private fun assignWidget(config: WidgetConfig) {
        config.widgetIds.add(pickWidgetId)
        ConfigStore.save(this, config)
        WidgetUpdater.updateNow(this, pickWidgetId)
        Toast.makeText(
            this,
            getString(R.string.toast_widget_assigned, config.name),
            Toast.LENGTH_SHORT
        ).show()
        finish()
    }

    private fun confirmDelete(config: WidgetConfig) {
        AlertDialog.Builder(this)
            .setTitle("删除「${config.name}」")
            .setMessage("删除后其桌面组件将变为占位状态，且不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                ConfigStore.delete(this, config.id)
                WidgetUpdater.renderAll(this)
                Toast.makeText(
                    this, getString(R.string.toast_deleted, config.name), Toast.LENGTH_SHORT
                ).show()
                reload()
            }
            .show()
    }

    private fun exportConfig(config: WidgetConfig) {
        val json = ConfigStore.exportJson(config)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("WidgetFlow config", json))
        Toast.makeText(this, R.string.export_ok, Toast.LENGTH_SHORT).show()
    }

    private fun showImportDialog() {
        val dv = DialogImportBinding.inflate(LayoutInflater.from(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.import_dialog_title)
            .setView(dv.root)
            .setNegativeButton("取消", null)
            .setPositiveButton("导入") { _, _ ->
                val text = dv.importText.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                val imported = ConfigStore.import(this, text)
                if (imported != null) {
                    Toast.makeText(this, R.string.import_ok, Toast.LENGTH_SHORT).show()
                    reload()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.import_fail, "JSON 无法解析或缺少必要字段"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
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
