package com.widgetflow.app.model

import com.widgetflow.app.net.JsonPath

/**
 * 按抽取规则从响应解析出 别名 -> 字符串值。
 * JSON 响应用 JSONPath；文本响应用正则或整段。
 */
fun resolveAliases(
    rules: List<ExtractRule>,
    json: Any?,
    body: String? = null
): MutableMap<String, String> {
    val map = mutableMapOf<String, String>()
    val text = body ?: ""
    rules.forEach { r ->
        map[r.alias] = when (r.type) {
            "regex" -> regexFirst(text, r.path)
            "text" -> text
            else -> {
                val v = JsonPath.get(json, r.path)
                if (v == null) "" else JsonPath.valueToString(v)
            }
        }
    }
    return map
}

/** 正则首匹配：优先取第 1 个捕获组，否则取整段匹配；无匹配或非法正则返回空串 */
fun regexFirst(text: String, pattern: String): String {
    if (pattern.isBlank()) return text
    return try {
        val m = Regex(pattern).find(text) ?: return ""
        val groups = m.groupValues
        if (groups.size > 1 && groups[1].isNotBlank()) groups[1] else m.value
    } catch (e: Exception) {
        ""
    }
}

/** 规则类型显示名 */
fun ExtractRule.typeLabel(): String = when (type) {
    "regex" -> "正则"
    "text" -> "文本"
    else -> "JSON"
}

/** 规则匹配方式描述（用于规则列表的示例说明） */
fun ExtractRule.describe(): String = when (type) {
    "regex" -> "匹配: $path"
    "text" -> "整段文本"
    else -> "路径: $path"
}

/** 渲染模板：把 {别名} 占位符替换为抽取值；{time} 为刷新时间；未知别名输出空串 */
fun renderTemplate(tpl: String, aliasMap: Map<String, String>, time: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < tpl.length) {
        val c = tpl[i]
        if (c == '{') {
            val end = tpl.indexOf('}', i + 1)
            if (end > i) {
                val name = tpl.substring(i + 1, end).trim()
                sb.append(
                    when {
                        name == "time" -> time
                        aliasMap.containsKey(name) -> aliasMap[name] ?: ""
                        else -> ""
                    }
                )
                i = end + 1
                continue
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/** 检查模板中引用的别名是否都存在，返回缺失的别名列表 */
fun missingAliases(tpl: String, aliases: Set<String>): List<String> {
    val missing = mutableListOf<String>()
    var i = 0
    while (i < tpl.length) {
        if (tpl[i] == '{') {
            val end = tpl.indexOf('}', i + 1)
            if (end > i) {
                val name = tpl.substring(i + 1, end).trim()
                if (name != "time" && name.isNotEmpty() && !aliases.contains(name)) {
                    missing.add(name)
                }
                i = end + 1
                continue
            }
        }
        i++
    }
    return missing
}
