package com.briqt.moke.ui

import com.briqt.moke.data.ExtraKeysLayout
import com.briqt.moke.terminal.Modifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 面板/常驻两排的键表是手写的，这里钉住"每个键都真的能发出字节、排布不超宽"。 */
class KeySectionsTest {

    private val plain = Modifiers()

    private fun allKeys(rows: List<List<ExtraKey>>) = rows.flatten()

    @Test
    fun `每个普通键都能编码出非空字节`() {
        val rows = KEY_SECTIONS.flatMap { it.rows } + DEFAULT_EXTRA_KEYS
        allKeys(rows).filterIsInstance<ExtraKey.Key>().forEach { key ->
            assertTrue("按键 ${key.label} 编码为空", plain.encode(key.key).isNotEmpty())
        }
    }

    /** 键均分宽度不滚动：一排超过 7 个就会挤到看不清。 */
    @Test
    fun `每排不超过 7 个键`() {
        (KEY_SECTIONS.flatMap { it.rows } + DEFAULT_EXTRA_KEYS).forEach { row ->
            assertTrue("一排 ${row.size} 个键，超过 7", row.size <= 7)
            assertTrue("空行", row.isNotEmpty())
        }
    }

    @Test
    fun `常驻两排保留更多键与文本段入口`() {
        val actions = allKeys(DEFAULT_EXTRA_KEYS).filterIsInstance<ExtraKey.Action>().map { it.id }
        assertTrue(ACTION_PANEL in actions)
        assertTrue(ACTION_COMPOSER in actions)
    }

    /** 收录标准：软键盘打得出的字面字符不占位（rc.3 砍掉整页符号后的回归闸门）。 */
    @Test
    fun `键表里没有字面字符键`() {
        val rows = KEY_SECTIONS.flatMap { it.rows } + DEFAULT_EXTRA_KEYS
        val chars = allKeys(rows).filterIsInstance<ExtraKey.Key>()
            .filter { it.key is com.briqt.moke.terminal.KeyId.Chars }
        assertTrue("这些键输入法本来就能打：${chars.map { it.label }}", chars.isEmpty())
    }

    /** 面板浮在常驻两排之上，同一个键出现两次只会让人不知道该按哪个。 */
    @Test
    fun `面板不与常驻两排重复`() {
        val resident = allKeys(DEFAULT_EXTRA_KEYS).filterIsInstance<ExtraKey.Key>().map { it.key }.toSet()
        val dup = allKeys(KEY_SECTIONS.flatMap { it.rows }).filterIsInstance<ExtraKey.Key>()
            .filter { it.key in resident }
        assertTrue("面板与常驻两排重复：${dup.map { it.label }}", dup.isEmpty())
    }

    /** 修饰键三态的高亮共用一份状态，同一个修饰键出现两处会各画各的。 */
    @Test
    fun `每个修饰键只出现一次`() {
        val mods = allKeys(KEY_SECTIONS.flatMap { it.rows } + DEFAULT_EXTRA_KEYS)
            .filterIsInstance<ExtraKey.Mod>().map { it.kind }
        assertEquals(mods.distinct().size, mods.size)
    }

    /** 倒 T 方向键：↑ 必须正对着 ↓，否则拇指要重新找位置。 */
    @Test
    fun `常驻两排是倒 T 方向键`() {
        assertEquals(7, DEFAULT_EXTRA_KEYS[0].size)
        assertEquals(7, DEFAULT_EXTRA_KEYS[1].size)
        assertEquals("↑", DEFAULT_EXTRA_KEYS[0][3].label)
        assertEquals(listOf("←", "↓", "→"), DEFAULT_EXTRA_KEYS[1].subList(2, 5).map { it.label })
    }

    @Test
    fun `功能键分段覆盖 F1 到 F12`() {
        val fnSection = KEY_SECTIONS.first { section ->
            allKeys(section.rows).any { it.label == "F1" }
        }
        val labels = allKeys(fnSection.rows).map { it.label }
        assertEquals((1..12).map { "F$it" }, labels)
    }

    @Test
    fun `every preset has twelve configurable keys and two fixed rightmost actions`() {
        ExtraKeysLayout.entries.forEach { layout ->
            val rows = layoutRows(layout, DEFAULT_KEY_IDS)
            assertEquals(layout.name, listOf(7, 7), rows.map { it.size })
            assertTrue(layout.name, rows.flatMap { it.take(6) }.none { it is ExtraKey.Action })
            assertEquals(ExtraKey.Action(ACTION_PANEL), rows[0].last())
            assertEquals(ExtraKey.Action(ACTION_COMPOSER), rows[1].last())
        }
    }

    @Test
    fun `custom layout preserves chosen slots and fixed rightmost actions`() {
        val chosen = listOf("F12", "CTRL", "^C", "←", "Enter", "ESC", "F1", "F1", "TAB", "⌫", "PgDn", "SHIFT")
        val rows = layoutRows(ExtraKeysLayout.CUSTOM, chosen)
        assertEquals(chosen.take(6), rows[0].take(6).map { it.label })
        assertEquals(chosen.drop(6), rows[1].take(6).map { it.label })
        assertEquals(ExtraKey.Action(ACTION_PANEL), rows[0].last())
        assertEquals(ExtraKey.Action(ACTION_COMPOSER), rows[1].last())
        assertEquals("\u001b[24~", plain.encode((rows[0][0] as ExtraKey.Key).key))
    }

    @Test
    fun `invalid stored custom keys cannot replace fixed actions or break toolbar`() {
        for (stored in listOf(listOf("ESC"), List(12) { "panel" })) {
            assertEquals(DEFAULT_KEY_IDS, layoutKeyIds(ExtraKeysLayout.CUSTOM, stored))
        }
    }

    @Test
    fun `function preset shows F1 to F12 across two horizontal rows`() {
        val rows = layoutRows(ExtraKeysLayout.FUNCTION, emptyList())
        assertEquals((1..6).map { "F$it" }, rows[0].take(6).map { it.label })
        assertEquals((7..12).map { "F$it" }, rows[1].take(6).map { it.label })
    }
}
