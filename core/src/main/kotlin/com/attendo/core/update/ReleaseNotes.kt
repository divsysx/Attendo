package com.attendo.core.update

/**
 * Renders a GitHub release body as the plain text the update card shows.
 *
 * Release notes are written in Markdown for the Releases page, and the card is not a
 * Markdown view: handed the source verbatim it would show `## What's new` and `**fixed**`
 * as literal characters. This is a deliberately small normalisation — headings, emphasis
 * markers, code ticks and list bullets — not a Markdown engine. Anything it does not
 * recognise passes through untouched, because release notes are human writing and the
 * worst failure here is a stray character, not a wrong number.
 */
object ReleaseNotes {

    /** The body as readable plain text, with runs of blank lines collapsed to one. */
    fun plain(markdown: String): String = markdown
        .lineSequence()
        .map(::plainLine)
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    private fun plainLine(line: String): String {
        var text = line.trim()
        // A heading is just a title line here; drop the hashes and their following space.
        text = text.replace(Regex("^#{1,6}\\s+"), "")
        // Emphasis and inline code markers carry no meaning on a plain card.
        text = text.replace("**", "").replace("__", "").replace("`", "")
        // List markers become bullets, so "- fixed the thing" and "* fixed the thing" read alike.
        text = text.replace(Regex("^[-*+]\\s+"), "• ")
        return text
    }
}
