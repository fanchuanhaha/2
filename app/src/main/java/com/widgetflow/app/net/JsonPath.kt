package com.widgetflow.app.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSONPath 子集解析器（PRD 第 08 章：仅支持 $ / .key / [n] / [*]）
 * [*] 通配取数组第一项。
 */
object JsonPath {

    private sealed class Tok
    private data class Key(val name: String) : Tok()
    private data class Idx(val i: Int) : Tok()

    private fun tokenize(path: String): List<Tok> {
        val toks = mutableListOf<Tok>()
        val s = path.trim()
        var i = 0
        if (s.startsWith("$")) i = 1
        while (i < s.length) {
            when (s[i]) {
                '.' -> {
                    i++
                    val sb = StringBuilder()
                    while (i < s.length && s[i] != '.' && s[i] != '[') {
                        sb.append(s[i]); i++
                    }
                    if (sb.isNotEmpty()) toks.add(Key(sb.toString()))
                }
                '[' -> {
                    i++
                    if (i < s.length && s[i] == '*') {
                        toks.add(Idx(0)); i += 2
                    } else {
                        val sb = StringBuilder()
                        while (i < s.length && s[i] != ']') {
                            sb.append(s[i]); i++
                        }
                        i++ // 跳过 ]
                        toks.add(Idx(sb.toString().toIntOrNull() ?: 0))
                    }
                }
                else -> i++
            }
        }
        return toks
    }

    /** 沿路径取值；任一步取不到返回 null */
    fun get(root: Any?, path: String): Any? {
        var node = root ?: return null
        for (t in tokenize(path)) {
            node = when (t) {
                is Key -> if (node is JSONObject && node.has(t.name)) node.get(t.name) else return null
                is Idx -> if (node is JSONArray && t.i < node.length()) node.get(t.i) else return null
            }
        }
        return node
    }

    fun valueToString(v: Any?): String = when (v) {
        null -> "null"
        is String -> v
        is JSONObject, is JSONArray -> v.toString()
        else -> v.toString()
    }

    fun pretty(v: Any?): String = try {
        when (v) {
            is JSONObject -> v.toString(2)
            is JSONArray -> v.toString(2)
            else -> valueToString(v)
        }
    } catch (e: Exception) {
        valueToString(v)
    }
}
