package com.devreply.sdk

/**
 * DevReply Markdown (spec 05, "Markdown in team messages"): the small subset the team and agents write,
 * parsed to the same tree on every platform. A faithful port of `sdk/conformance/markdown/reference.py`;
 * `MarkdownTest` runs every case in `sdk/conformance/markdown/cases.json`. No HTML, no dependencies.
 */
internal object Markdown {
    /** A run of text with the marks that are on. [link] is an http(s) or mailto URL. */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    ) {
        fun sameMarks(o: Span) = bold == o.bold && italic == o.italic && strike == o.strike && code == o.code && link == o.link
    }

    sealed interface Node {
        data class Paragraph(val spans: List<Span>) : Node
        data class Heading(val spans: List<Span>) : Node
        data class Quote(val spans: List<Span>) : Node
        data class ListBlock(val ordered: Boolean, val start: Int, val items: List<List<Span>>) : Node
        data class Code(val text: String) : Node
    }

    private const val ESCAPABLE = "\\`*_~[]()#>!+-."
    private val SCHEMES = listOf("http://", "https://", "mailto:")

    /** Python's `str.isalnum` for the character at a code point (letters and every kind of number). */
    private fun alnum(cp: Int): Boolean = Character.isLetterOrDigit(cp) ||
        Character.getType(cp).let { it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt() }

    private fun alnumBefore(s: String, i: Int) = i > 0 && alnum(s.codePointBefore(i))
    private fun alnumAt(s: String, i: Int) = i < s.length && alnum(s.codePointAt(i))

    private fun findCloser(s: String, i: Int, tok: String): Int {
        val n = tok.length
        var k = s.indexOf(tok, i + n)
        while (k != -1) {
            var ok = k > i + n && !s[k - 1].isWhitespace()
            if (ok && n == 1) ok = s[k - 1] != tok[0] && (k + 1 >= s.length || s[k + 1] != tok[0])
            if (ok && tok[0] == '_') ok = !alnumAt(s, k + n)
            if (ok) return k
            k = s.indexOf(tok, k + 1)
        }
        return -1
    }

    private val EMPHASIS = listOf("***", "**", "__", "~~", "*", "_")

    private fun Span.with(tok: String): Span = when (tok) {
        "***" -> copy(bold = true, italic = true)
        "**", "__" -> copy(bold = true)
        "~~" -> copy(strike = true)
        else -> copy(italic = true)
    }

    /** Inline marks, left to right. [marks]: the marks already on (its text is ignored). */
    fun inline(s: String, marks: Span = Span("")): List<Span> {
        val out = ArrayList<Span>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(marks.copy(text = buf.toString()))
                buf.setLength(0)
            }
        }

        var i = 0
        outer@ while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE) {
                buf.append(s[i + 1]); i += 2; continue
            }
            if (c == '`') {
                val k = s.indexOf('`', i + 1)
                if (k != -1) {
                    flush()
                    out.add(marks.copy(text = s.substring(i + 1, k), code = true))
                    i = k + 1; continue
                }
            }
            if (c == '[') {
                val close = s.indexOf("](", i + 1)
                val end = if (close != -1) s.indexOf(')', close + 2) else -1
                if (close != -1 && end != -1 && ' ' !in s.substring(close + 2, end) && close > i + 1) {
                    val url = s.substring(close + 2, end)
                    flush()
                    val inner = if (SCHEMES.any { url.startsWith(it) }) marks.copy(link = url) else marks
                    out.addAll(inline(s.substring(i + 1, close), inner))
                    i = end + 1; continue
                }
            }
            if (c == 'h' && (s.startsWith("http://", i) || s.startsWith("https://", i)) &&
                (i == 0 || s[i - 1].isWhitespace() || s[i - 1] == '(')
            ) {
                var j = i
                while (j < s.length && !s[j].isWhitespace()) j++
                val url = s.substring(i, j).trimEnd('.', ',', ';', ':', '!', '?', ')')
                if (url.length > "https://".length) {
                    flush()
                    out.add(marks.copy(text = url, link = url))
                    i += url.length; continue
                }
            }
            for (tok in EMPHASIS) {
                if (!s.startsWith(tok, i)) continue
                val n = tok.length
                if (i + n >= s.length || s[i + n].isWhitespace()) break
                if (tok[0] == '_' && alnumBefore(s, i)) break
                val k = findCloser(s, i, tok)
                if (k == -1) break
                flush()
                out.addAll(inline(s.substring(i + n, k), marks.with(tok)))
                i = k + n
                continue@outer
            }
            // A run of marker characters that opened nothing stays literal as a whole.
            if (c == '*' || c == '_' || c == '~') {
                var j = i
                while (j < s.length && s[j] == c) j++
                buf.append(s, i, j); i = j; continue
            }
            buf.append(c); i++
        }
        flush()
        return merge(out)
    }

    private fun merge(spans: List<Span>): List<Span> {
        val res = ArrayList<Span>()
        for (sp in spans) {
            if (sp.text.isEmpty()) continue
            val last = res.lastOrNull()
            if (last != null && last.sameMarks(sp)) res[res.size - 1] = last.copy(text = last.text + sp.text) else res.add(sp)
        }
        return res
    }

    // The line rules, matched by hand rather than with regular expressions: Android's ICU patterns and the JVM's
    // disagree on Unicode \s and \d, and Python's (the reference) are Unicode. Whitespace = Char.isWhitespace.

    /** `^\s*(#{1,6})\s+(.*?)\s*$`: the heading's text, or null. */
    private fun heading(line: String): String? {
        var i = 0
        while (i < line.length && line[i].isWhitespace()) i++
        val hashes = i
        while (i < line.length && line[i] == '#') i++
        if (i - hashes !in 1..6 || i >= line.length || !line[i].isWhitespace()) return null
        return line.substring(i).trim()
    }

    /** `^\s*>\s?(.*)$`: the quoted text (trimmed), or null. */
    private fun quote(line: String): String? {
        var i = 0
        while (i < line.length && line[i].isWhitespace()) i++
        if (i >= line.length || line[i] != '>') return null
        return line.substring(i + 1).trim()
    }

    /** At most one leading whitespace character, as `^\s{0,1}`. */
    private fun indent(line: String) = if (line.isNotEmpty() && line[0].isWhitespace()) 1 else 0

    /** `^\s{0,1}[-*+]\s+(.*)$`: the item's text (trimmed), or null. */
    private fun bullet(line: String): String? {
        val i = indent(line)
        if (i + 1 >= line.length || line[i] !in "-*+" || !line[i + 1].isWhitespace()) return null
        return line.substring(i + 1).trim()
    }

    /** `^\s{0,1}(\d{1,9})[.)]\s+(.*)$`: the item's number and text (trimmed), or null. */
    private fun numbered(line: String): Pair<Int, String>? {
        val start = indent(line)
        var i = start
        var n = 0
        while (i < line.length && Character.isDigit(line[i])) {
            n = n * 10 + Character.digit(line[i], 10); i++
        }
        if (i - start !in 1..9 || i + 1 >= line.length || line[i] !in ".)" || !line[i + 1].isWhitespace()) return null
        return n to line.substring(i + 1).trim()
    }

    private sealed interface Open {
        class Lines(val quote: Boolean, val lines: MutableList<String>) : Open
        class Items(val ordered: Boolean, val start: Int, val items: MutableList<MutableList<String>>) : Open
    }

    /** The message's blocks. Never throws: anything unrecognised is paragraph text. */
    fun parse(md: String): List<Node> {
        val lines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<Node>()
        var cur: Open? = null

        fun close() {
            when (val c = cur) {
                is Open.Lines -> {
                    val spans = inline(c.lines.joinToString("\n"))
                    blocks.add(if (c.quote) Node.Quote(spans) else Node.Paragraph(spans))
                }
                is Open.Items -> blocks.add(Node.ListBlock(c.ordered, c.start, c.items.map { inline(it.joinToString("\n")) }))
                null -> {}
            }
            cur = null
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val stripped = line.trim()
            if (stripped.isEmpty()) {
                close(); i++; continue
            }
            if (stripped.startsWith("```")) {
                close()
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    body.add(lines[i]); i++
                }
                blocks.add(Node.Code(body.joinToString("\n")))
                i++; continue
            }
            val heading = heading(line)
            if (heading != null) {
                close()
                blocks.add(Node.Heading(inline(heading)))
                i++; continue
            }
            val quote = quote(line)
            if (quote != null) {
                val c = cur
                val open = if (c is Open.Lines && c.quote) c else Open.Lines(true, ArrayList()).also { close(); cur = it }
                open.lines.add(quote)
                i++; continue
            }
            val bullet = bullet(line)
            val numbered = numbered(line)
            if (bullet != null || numbered != null) {
                val ordered = numbered != null
                val text = numbered?.second ?: bullet ?: ""
                val c = cur
                val open = if (c is Open.Items && c.ordered == ordered) c else {
                    close()
                    Open.Items(ordered, numbered?.first ?: 1, ArrayList()).also { cur = it }
                }
                open.items.add(mutableListOf(text))
                i++; continue
            }
            val c = cur
            if (c is Open.Items && (line.startsWith("  ") || line.startsWith("\t"))) {
                c.items.last().add(stripped)
                i++; continue
            }
            if (c is Open.Lines && !c.quote) {
                c.lines.add(stripped)
            } else {
                close(); cur = Open.Lines(false, mutableListOf(stripped))
            }
            i++
        }
        close()
        return blocks
    }

    /** Spans as plain text: links as "text (url)" unless the text is the url (mailto: shows the address). */
    fun plainSpans(spans: List<Span>): String {
        val out = StringBuilder()
        var k = 0
        while (k < spans.size) {
            val link = spans[k].link
            if (link != null) {
                val text = StringBuilder()
                while (k < spans.size && spans[k].link == link) {
                    text.append(spans[k].text); k++
                }
                val shown = if (link.startsWith("mailto:")) link.removePrefix("mailto:") else link
                val t = text.toString()
                out.append(if (t == link || t == shown) t else "$t ($shown)")
            } else {
                out.append(spans[k].text); k++
            }
        }
        return out.toString()
    }

    /** The plain-text version (the server's `fallback`): blocks joined by a blank line, list items "• x" / "1. x". */
    fun plain(blocks: List<Node>): String = blocks.joinToString("\n\n") { b ->
        when (b) {
            is Node.Code -> b.text
            is Node.ListBlock -> b.items.withIndex().joinToString("\n") { (n, item) ->
                (if (b.ordered) "${b.start + n}. " else "• ") + plainSpans(item)
            }
            is Node.Paragraph -> plainSpans(b.spans)
            is Node.Heading -> plainSpans(b.spans)
            is Node.Quote -> plainSpans(b.spans)
        }
    }
}
