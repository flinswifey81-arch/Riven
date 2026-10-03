package com.shai.riven.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    @Test
    fun rendersParagraphsEmphasisListsAndCodeWithoutRawMarkers() {
        val rendered = chatMarkdown(
            "**Bold** and *soft*\n\n- first\n- `second`\n\n```kotlin\nval answer = 42\n```",
        )

        assertEquals(
            "Bold and soft\n\n• first\n• second\n\nval answer = 42",
            rendered.text,
        )
        assertFalse(rendered.text.contains("**"))
        assertFalse(rendered.text.contains("```"))
        assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(rendered.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(rendered.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun incompleteStreamingDelimitersStayVisibleUntilClosed() {
        assertEquals("Reply **still streaming", chatMarkdown("Reply **still streaming").text)
        assertEquals("```json\n{\"partial\": true}", chatMarkdown("```json\n{\"partial\": true}").text)
        assertEquals("An `unfinished code span", chatMarkdown("An `unfinished code span").text)
    }

    @Test
    fun onlySafeWebLinksReceiveAnnotationsAndNoRemoteMediaLoads() {
        val rendered = chatMarkdown(
            "[docs](https://example.com/help) [unsafe](javascript:alert(1)) ![remote](https://example.com/a.png)",
        )

        assertEquals(
            "docs [unsafe](javascript:alert(1)) ![remote](https://example.com/a.png)",
            rendered.text,
        )
        val links = rendered.getStringAnnotations(URL_ANNOTATION, 0, rendered.length)
        assertEquals(1, links.size)
        assertEquals("https://example.com/help", links.single().item)
    }

    @Test
    fun mixedNestedFormattingAndEscapesRemainDeterministic() {
        val rendered = chatMarkdown("## A **bold and *soft*** title\nLiteral \\*asterisk\\*")

        assertEquals("A bold and soft title\nLiteral *asterisk*", rendered.text)
        assertTrue(rendered.spanStyles.count { it.item.fontWeight == FontWeight.Bold } >= 1)
        assertTrue(rendered.spanStyles.count { it.item.fontStyle == FontStyle.Italic } >= 1)
    }
}
