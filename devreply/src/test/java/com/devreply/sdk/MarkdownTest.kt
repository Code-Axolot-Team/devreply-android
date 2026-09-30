package com.devreply.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** DevReply Markdown (spec 05): the same tree as every other parser (sdk/conformance/markdown/cases.json). */
class MarkdownTest {
    private fun conformance(name: String): File {
        // Gradle runs unit tests from the module directory (sdk/android/devreply).
        val candidates = listOf("../../conformance/markdown/$name", "../conformance/markdown/$name", "sdk/conformance/markdown/$name")
        return candidates.map(::File).first { it.exists() }
    }

    private fun spans(a: JSONArray): List<Markdown.Span> = (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Markdown.Span(
            text = o.getString("text"),
            bold = o.optBoolean("bold"),
            italic = o.optBoolean("italic"),
            strike = o.optBoolean("strike"),
            code = o.optBoolean("code"),
            link = o.str("link"),
        )
    }

    private fun node(o: JSONObject): Markdown.Node = when (val type = o.getString("type")) {
        "paragraph" -> Markdown.Node.Paragraph(spans(o.getJSONArray("spans")))
        "heading" -> Markdown.Node.Heading(spans(o.getJSONArray("spans")))
        "quote" -> Markdown.Node.Quote(spans(o.getJSONArray("spans")))
        "code" -> Markdown.Node.Code(o.getString("text"))
        "list" -> o.getJSONArray("items").let { items ->
            Markdown.Node.ListBlock(o.getBoolean("ordered"), o.getInt("start"), (0 until items.length()).map { spans(items.getJSONArray(it)) })
        }
        else -> error("unknown block $type")
    }

    @Test fun everyConformanceCaseGivesTheSameTreeAndPlainText() {
        val cases = JSONObject(conformance("cases.json").readText()).getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val expected = c.getJSONArray("blocks").let { a -> (0 until a.length()).map { node(a.getJSONObject(it)) } }
            val parsed = Markdown.parse(c.getString("markdown"))
            assertEquals(name, expected, parsed)
            assertEquals("$name (plain)", c.getString("plain"), Markdown.plain(parsed))
        }
        assertTrue("the whole suite", cases.length() >= 30)
    }

    private fun message(blocks: String) = Message.parse(
        JSONObject(
            """{"id":"01a0e892-b653-7191-9df9-c7e19fc68be7","author":"agent",
               "created_at":"2026-09-30T10:00:00Z","blocks":$blocks}""",
        ),
    )

    @Test fun aMarkdownBlockWithoutMinSdkIsFormatted() {
        val m = message("""[{"type":"markdown","text":"Tap **Export**","fallback":"Tap Export"}]""")
        val block = m.blocks.single() as Block.Markdown
        assertEquals(
            listOf(Markdown.Node.Paragraph(listOf(Markdown.Span("Tap "), Markdown.Span("Export", bold = true)))),
            block.nodes,
        )
        // Previews and system lines use the plain text, never the raw "**".
        assertEquals("Tap Export", m.plainText)
    }

    @Test fun withoutAFallbackThePlainTextComesFromTheSameParse() {
        val m = message("""[{"type":"markdown","text":"- one\n- [docs](https://a.b)"}]""")
        assertEquals("• one\n• docs (https://a.b)", m.plainText)
    }

    @Test fun minSdk050GivesTheFallbackUntilThisSdkIs050() {
        val m = message("""[{"type":"markdown","text":"Tap **Export**","min_sdk":"0.5.0","fallback":"Tap Export"}]""")
        if (Block.isVersion(DEVREPLY_SDK_VERSION, olderThan = "0.5.0")) {
            // Today's build (0.4.4): the server's real blocks show their plain text.
            assertEquals(listOf(Block.Unsupported("Tap Export")), m.blocks)
        } else {
            assertTrue(m.blocks.single() is Block.Markdown)
        }
        assertEquals("Tap Export", m.plainText)
        val newer = message("""[{"type":"markdown","text":"**x**","min_sdk":"99.0.0","fallback":"x"}]""")
        assertEquals(listOf(Block.Unsupported("x")), newer.blocks)
    }

    @Test fun aMarkdownBlockWithoutTextShowsItsFallback() {
        val m = message("""[{"type":"markdown","fallback":"Plain"}]""")
        assertEquals(listOf(Block.Unsupported("Plain")), m.blocks)
    }
}
