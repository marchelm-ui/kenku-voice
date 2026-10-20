package com.kenku.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * A [tag] that matches nothing plays silence at the table, so the matcher is tested
 * two ways: ladder behavior on a synthetic catalog, and full resolution against the
 * real catalog.json — every label and every category key a user can insert must find
 * a sound.
 */
class SfxMatchTest {

    private fun sfx(id: String, label: String, cat: String) = Sfx(id, label, "sfx/$cat/$id.ogg", 1f, cat)

    private val cats = listOf(
        SfxCategory("mimicry", "My Mimicry", "🦜", "", emptyList()),
        SfxCategory("coins", "Coins & Treasure", "🪙", "gold, gems and loot", listOf(
            sfx("c1", "Coin toss", "coins"), sfx("c2", "Coins pouring", "coins"))),
        SfxCategory("doors", "Doors", "🚪", "creaks and slams", listOf(
            sfx("d1", "Door creak", "doors"), sfx("d2", "Heavy door slam", "doors"))),
    )

    @Test
    fun `exact label wins over looser matches`() {
        val s = SfxMatch.find(cats, "coin toss", Random(1))
        assertEquals("c1", s?.id)
    }

    @Test
    fun `singular and plural tags both resolve`() {
        assertNotNull(SfxMatch.find(cats, "coin", Random(1)))
        assertNotNull(SfxMatch.find(cats, "coins", Random(1)))
        assertNotNull(SfxMatch.find(cats, "door creaks", Random(1)))
    }

    @Test
    fun `a category tag picks one of its sounds`() {
        val s = SfxMatch.find(cats, "doors", Random(2))
        assertEquals("doors", s?.category)
    }

    @Test
    fun `an empty matching category falls through instead of crashing`() {
        // "mimicry" matches the empty My Mimicry category; it used to throw on .random().
        assertNull(SfxMatch.find(cats, "mimicry", Random(3)))
        // And with a recording present, the same tag resolves to it.
        val withRec = cats.map { if (it.key == "mimicry") it.copy(sounds = listOf(sfx("m1", "Tavern laugh", "mimicry"))) else it }
        assertEquals("m1", SfxMatch.find(withRec, "mimicry", Random(3))?.id)
    }

    @Test
    fun `nonsense and empty tags return null quietly`() {
        assertNull(SfxMatch.find(cats, "xylophone quartet", Random(4)))
        assertNull(SfxMatch.find(cats, "", Random(4)))
        assertNull(SfxMatch.find(cats, "!!", Random(4)))
    }

    // ---------- against the real catalog ----------

    private fun realCats(): List<SfxCategory> {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/assets").exists() && !File(dir, "src/main/assets").exists()) dir = dir.parentFile
        val assets = if (File(dir!!, "app/src/main/assets").exists()) File(dir, "app/src/main/assets") else File(dir, "src/main/assets")
        val arr = JSONObject(File(assets, "sfx/catalog.json").readText()).getJSONArray("categories")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val s = c.getJSONArray("sounds")
            SfxCategory(c.getString("key"), c.getString("title"), c.getString("icon"), c.getString("desc"),
                (0 until s.length()).map { j ->
                    val o = s.getJSONObject(j)
                    Sfx(o.getString("id"), o.getString("label"), o.getString("file"), o.optDouble("dur", 1.0).toFloat(), c.getString("key"))
                })
        }
    }

    @Test
    fun `every real sound label resolves to a sound`() {
        val cats = realCats()
        val unresolved = cats.flatMap { it.sounds }.filter { SfxMatch.find(cats, it.label, Random(5)) == null }
        assertTrue("labels that match nothing: ${unresolved.take(5).map { it.label }}", unresolved.isEmpty())
    }

    @Test
    fun `every real category key resolves — the 'any sound' picker depends on it`() {
        val cats = realCats()
        val unresolved = cats.map { it.key }.filter { SfxMatch.find(cats, it, Random(6)) == null }
        assertTrue("category keys that match nothing: $unresolved", unresolved.isEmpty())
    }
}
