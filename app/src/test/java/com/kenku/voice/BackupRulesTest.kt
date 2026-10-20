package com.kenku.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The backup rules decide what a new phone inherits. Both formats (pre-12 and 12+,
 * cloud and device transfer) must stay in lockstep: the irreplaceable data (clones,
 * mimic recordings, the lines index, settings) goes in, the regenerable line audio
 * stays out so the backup quota never silently kills the whole backup.
 */
class BackupRulesTest {

    private fun resXml(name: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            for (p in listOf("app/src/main/res/xml/$name", "src/main/res/xml/$name")) {
                val f = File(dir, p)
                if (f.exists()) return f
            }
            dir = dir.parentFile
        }
        throw AssertionError("$name not found above ${System.getProperty("user.dir")}")
    }

    private fun rules(parent: org.w3c.dom.Element): Pair<Set<String>, Set<String>> {
        fun collect(tag: String): Set<String> {
            val nodes = parent.getElementsByTagName(tag)
            return (0 until nodes.length).map {
                val e = nodes.item(it) as org.w3c.dom.Element
                e.getAttribute("domain") + ":" + e.getAttribute("path")
            }.toSet()
        }
        return collect("include") to collect("exclude")
    }

    private val mustInclude = setOf("file:clones", "file:mimic", "file:lines.json", "sharedpref:kenku.xml")
    private val mustExclude = setOf("file:lines")

    @Test
    fun `legacy backup rules keep the irreplaceable and drop the bulk`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resXml("backup_rules.xml"))
        val (inc, exc) = rules(doc.documentElement)
        assertEquals(mustInclude, inc)
        assertEquals(mustExclude, exc)
    }

    @Test
    fun `modern rules match for cloud backup and device transfer alike`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resXml("data_extraction_rules.xml"))
        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = doc.getElementsByTagName(section)
            assertEquals("missing <$section>", 1, nodes.length)
            val (inc, exc) = rules(nodes.item(0) as org.w3c.dom.Element)
            assertEquals("$section includes", mustInclude, inc)
            assertEquals("$section excludes", mustExclude, exc)
        }
    }

    @Test
    fun `the manifest actually points at both rule files`() {
        val manifest = resXml("backup_rules.xml").parentFile.parentFile.parentFile.resolve("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:allowBackup=\"true\""))
    }
}
