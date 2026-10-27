package com.example.ilauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

const val COLS = 4
const val ROWS = 6

sealed class Item {
    var row = 0
    var col = 0
    open val sc: Int get() = 1
    open val sr: Int get() = 1
}

class AppItem(val key: String) : Item()
class FolderItem(var name: String, val keys: MutableList<String>) : Item()
class WidgetItem(val id: Int, val spanC: Int, val spanR: Int) : Item() {
    override val sc: Int get() = spanC
    override val sr: Int get() = spanR
}

class HomeLayout(val pages: MutableList<MutableList<Item>>, val dock: MutableList<String>) {

    fun itemAt(page: Int, r: Int, c: Int, ignore: Item?): Item? =
        pages[page].firstOrNull {
            it !== ignore && r >= it.row && r < it.row + it.sr && c >= it.col && c < it.col + it.sc
        }

    fun isFree(page: Int, r: Int, c: Int, sc: Int, sr: Int, ignore: Item?): Boolean {
        if (r < 0 || c < 0 || r + sr > ROWS || c + sc > COLS) return false
        return pages[page].none {
            it !== ignore && r < it.row + it.sr && r + sr > it.row && c < it.col + it.sc && c + sc > it.col
        }
    }

    private fun findFree(page: Int, sc: Int, sr: Int): Pair<Int, Int>? {
        for (r in 0..(ROWS - sr)) for (c in 0..(COLS - sc)) {
            if (isFree(page, r, c, sc, sr, null)) return r to c
        }
        return null
    }

    fun place(item: Item, preferPage: Int = 0) {
        val start = preferPage.coerceIn(0, pages.size - 1)
        val order = (start until pages.size) + (0 until start)
        for (p in order) {
            val pos = findFree(p, item.sc, item.sr) ?: continue
            item.row = pos.first; item.col = pos.second
            pages[p].add(item)
            return
        }
        item.row = 0; item.col = 0
        pages.add(mutableListOf(item))
    }

    fun remove(item: Item) = pages.forEach { it.remove(item) }

    fun normalize() {
        for (p in pages) {
            for (i in p.toList()) {
                if (i is FolderItem) when (i.keys.size) {
                    0 -> p.remove(i)
                    1 -> {
                        val a = AppItem(i.keys[0]); a.row = i.row; a.col = i.col
                        p[p.indexOf(i)] = a
                    }
                }
            }
        }
    }

    fun trimEmpty() {
        pages.removeAll { it.isEmpty() }
        if (pages.isEmpty()) pages.add(mutableListOf())
    }
}

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("ilauncher", Context.MODE_PRIVATE)

    fun save(c: Context, l: HomeLayout) {
        val pages = JSONArray()
        for (p in l.pages) {
            val arr = JSONArray()
            for (i in p) {
                val o = JSONObject().put("r", i.row).put("c", i.col)
                when (i) {
                    is AppItem -> o.put("t", "a").put("k", i.key)
                    is FolderItem -> o.put("t", "f").put("n", i.name).put("keys", JSONArray(i.keys))
                    is WidgetItem -> o.put("t", "w").put("id", i.id).put("sc", i.spanC).put("sr", i.spanR)
                }
                arr.put(o)
            }
            pages.put(arr)
        }
        val root = JSONObject().put("pages", pages).put("dock", JSONArray(l.dock))
        prefs(c).edit().putString("layout2", root.toString()).apply()
    }

    fun load(c: Context): HomeLayout? {
        val s = prefs(c).getString("layout2", null) ?: return null
        return try {
            val root = JSONObject(s)
            val pages = mutableListOf<MutableList<Item>>()
            val pa = root.getJSONArray("pages")
            for (i in 0 until pa.length()) {
                val arr = pa.getJSONArray(i)
                val list = mutableListOf<Item>()
                for (j in 0 until arr.length()) {
                    val o = arr.getJSONObject(j)
                    val item: Item = when (o.getString("t")) {
                        "a" -> AppItem(o.getString("k"))
                        "f" -> {
                            val ka = o.getJSONArray("keys")
                            FolderItem(o.getString("n"), MutableList(ka.length()) { ka.getString(it) })
                        }
                        else -> WidgetItem(o.getInt("id"), o.getInt("sc"), o.getInt("sr"))
                    }
                    item.row = o.getInt("r"); item.col = o.getInt("c")
                    list.add(item)
                }
                pages.add(list)
            }
            val da = root.getJSONArray("dock")
            val dock = MutableList(da.length()) { da.getString(it) }
            if (pages.isEmpty()) pages.add(mutableListOf())
            HomeLayout(pages, dock)
        } catch (e: Exception) {
            null
        }
    }
}
