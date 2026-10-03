package com.shai.riven.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import java.net.URI

/**
 * A deliberately bounded Markdown renderer for provider-authored chat text.
 *
 * It never interprets HTML or loads remote media. Incomplete streaming delimiters remain visible
 * until their closing delimiter arrives, so partial replies do not lose text while they stream.
 */
internal fun chatMarkdown(source: String): AnnotatedString {
    if (source.isEmpty()) return AnnotatedString("")
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    return buildAnnotatedString {
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            if (line.trimStart().startsWith(CODE_FENCE)) {
                val closingIndex = (lineIndex + 1 until lines.size).firstOrNull { candidate ->
                    lines[candidate].trim() == CODE_FENCE
                }
                if (closingIndex != null) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(lines.subList(lineIndex + 1, closingIndex).joinToString("\n"))
                    }
                    lineIndex = closingIndex + 1
                } else {
                    // A streaming response may not have delivered the closing fence yet.
                    append(line)
                    lineIndex += 1
                }
            } else {
                appendMarkdownLine(line)
                lineIndex += 1
            }
            if (lineIndex < lines.size) append('\n')
        }
    }
}

private fun AnnotatedString.Builder.appendMarkdownLine(line: String) {
    val heading = HEADING.matchEntire(line)
    if (heading != null) {
        append(heading.groupValues[1])
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            appendInlineMarkdown(heading.groupValues[3])
        }
        return
    }
    val bullet = BULLET.matchEntire(line)
    if (bullet != null) {
        append(bullet.groupValues[1])
        append("• ")
        appendInlineMarkdown(bullet.groupValues[3])
        return
    }
    val quote = QUOTE.matchEntire(line)
    if (quote != null) {
        append(quote.groupValues[1])
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
            append("› ")
            appendInlineMarkdown(quote.groupValues[2])
        }
        return
    }
    appendInlineMarkdown(line)
}

private fun AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    var index = 0
    while (index < text.length) {
        if (text[index] == '\\' && index + 1 < text.length && text[index + 1] in ESCAPABLE) {
            append(text[index + 1])
            index += 2
            continue
        }

        val codeEnd = if (text[index] == '`' && !text.startsWith(CODE_FENCE, index)) {
            text.indexOf('`', index + 1)
        } else {
            -1
        }
        if (codeEnd > index + 1) {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                append(text.substring(index + 1, codeEnd))
            }
            index = codeEnd + 1
            continue
        }

        val link = linkAt(text, index)
        if (link != null) {
            pushStringAnnotation(URL_ANNOTATION, link.url)
            withStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) {
                appendInlineMarkdown(link.label)
            }
            pop()
            index = link.endExclusive
            continue
        }

        val strongDelimiter = when {
            text.startsWith("**", index) -> "**"
            text.startsWith("__", index) -> "__"
            else -> null
        }
        if (strongDelimiter != null) {
            val end = findStrongEnd(text, strongDelimiter, index + strongDelimiter.length)
            if (end > index + strongDelimiter.length) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInlineMarkdown(text.substring(index + strongDelimiter.length, end))
                }
                index = end + strongDelimiter.length
                continue
            }
        }

        if (text.startsWith("~~", index)) {
            val end = text.indexOf("~~", index + 2)
            if (end > index + 2) {
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInlineMarkdown(text.substring(index + 2, end))
                }
                index = end + 2
                continue
            }
        }

        val emphasisDelimiter = text[index].takeIf { it == '*' || it == '_' }
        if (emphasisDelimiter != null && canOpenEmphasis(text, index)) {
            val end = findEmphasisEnd(text, emphasisDelimiter, index + 1)
            if (end > index + 1) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInlineMarkdown(text.substring(index + 1, end))
                }
                index = end + 1
                continue
            }
        }

        append(text[index])
        index += 1
    }
}

private fun findStrongEnd(text: String, delimiter: String, start: Int): Int {
    val candidate = text.indexOf(delimiter, start)
    if (candidate < 0) return -1
    return if (candidate + delimiter.length < text.length &&
        text[candidate + delimiter.length] == delimiter.first()
    ) {
        candidate + 1
    } else {
        candidate
    }
}

private fun linkAt(text: String, start: Int): MarkdownLink? {
    if (text[start] != '[' || start > 0 && text[start - 1] == '!') return null
    val labelEnd = text.indexOf(']', start + 1)
    if (labelEnd <= start + 1 || labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
    val urlEnd = text.indexOf(')', labelEnd + 2)
    if (urlEnd <= labelEnd + 2) return null
    val url = text.substring(labelEnd + 2, urlEnd).trim()
    if (!isSafeWebUrl(url)) return null
    return MarkdownLink(
        label = text.substring(start + 1, labelEnd),
        url = url,
        endExclusive = urlEnd + 1,
    )
}

private fun isSafeWebUrl(value: String): Boolean = try {
    val uri = URI(value)
    uri.scheme?.lowercase() in setOf("https", "http") &&
        !uri.host.isNullOrBlank() && uri.rawUserInfo == null
} catch (_: Exception) {
    false
}

private fun canOpenEmphasis(text: String, index: Int): Boolean {
    if (index + 1 >= text.length || text[index + 1].isWhitespace()) return false
    if (text[index] == '_' && index > 0 && text[index - 1].isLetterOrDigit()) return false
    return true
}

private fun findEmphasisEnd(text: String, delimiter: Char, start: Int): Int {
    var candidate = text.indexOf(delimiter, start)
    while (candidate >= 0) {
        val closesWordUnderscore = delimiter != '_' ||
            candidate + 1 >= text.length || !text[candidate + 1].isLetterOrDigit()
        if (candidate > start && !text[candidate - 1].isWhitespace() && closesWordUnderscore) return candidate
        candidate = text.indexOf(delimiter, candidate + 1)
    }
    return -1
}

private data class MarkdownLink(
    val label: String,
    val url: String,
    val endExclusive: Int,
)

internal const val URL_ANNOTATION = "chat_markdown_url"
private const val CODE_FENCE = "```"
private val HEADING = Regex("^( {0,3})(#{1,6})[ \\t]+(.+)$")
private val BULLET = Regex("^(\\s*)([-+*])[ \\t]+(.+)$")
private val QUOTE = Regex("^(\\s*)>[ \\t]?(.+)$")
private val ESCAPABLE = setOf('\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.', '!', '~')
