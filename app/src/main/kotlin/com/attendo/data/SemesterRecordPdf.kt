package com.attendo.data

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextUtils
import android.text.TextPaint
import com.attendo.core.model.Percent
import com.attendo.core.rollover.CourseRow
import com.attendo.core.rollover.SemesterRecordDoc
import com.attendo.core.rollover.SessionRow
import java.io.ByteArrayOutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Paints a [SemesterRecordDoc] into a PDF a viewer can open.
 *
 * This is the only Android-only piece of the semester-record feature, and it is deliberately
 * thin: every figure on the page was already chosen by [com.attendo.core.rollover.SemesterRecordRenderer],
 * which in turn delegates to [com.attendo.core.engine.AttendanceEngine.semesterStats]. Nothing is
 * recomputed here — the renderer hands over print-ready rows, and this class only lays them out on
 * A4 pages.
 *
 * Because the app's test dependencies are `junit` only — no PDF library, no Robolectric — this
 * class is not unit-tested. Its correctness rests on two things: the pure `SemesterRecordRenderer`
 * tests, which hold the *numbers* to the engine, and the layout here, which only places those
 * numbers. The split is what makes that a safe division of responsibility.
 *
 * The output is a byte array rather than a file: the caller has already asked Android's picker for a
 * `content://` URI, and [DocumentStore.write] is the one place a file is touched. Keeping this side
 * free of I/O means the renderer can be reused for a preview, an email attachment, or anything else
 * without dragging the picker contract along with it.
 */
object SemesterRecordPdf {

    /** Renders [doc] to one or more A4 pages and returns the raw PDF bytes. */
    fun export(doc: SemesterRecordDoc): ByteArray {
        val pdf = PdfDocument()
        try {
            PdfPageWriter(pdf).use { it.write(doc) }
            val out = ByteArrayOutputStream()
            pdf.writeTo(out)
            return out.toByteArray()
        } finally {
            // close() releases the page buffers even if writeTo failed, so a half-written page
            // never lingers in memory.
            pdf.close()
        }
    }
}

/**
 * Lays a [SemesterRecordDoc] out across A4 pages, starting a new page whenever the cursor runs
 * off the bottom and redrawing each table's header on the fresh page.
 *
 * The cursor model is top-down: [y] is the top of the next line, and a line's baseline is
 * `y - paint.ascent()` (ascent is negative, so the baseline sits a little below the top). After
 * drawing, [y] advances by the line height. [ensureSpace] page-breaks before a draw that would
 * otherwise cross the bottom margin.
 *
 * A4 at 72 points to the inch is 595 × 842, with a 40-point margin all round leaving a 515-point
 * content width — the number the table column maths below adds up to.
 */
private class PdfPageWriter(private val document: PdfDocument) : AutoCloseable {

    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 40f
    private val contentWidth = pageWidth - 2 * margin // 515

    private var pageNumber = 1
    private var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
    private var page = document.startPage(pageInfo)
    private var canvas = page.canvas
    private var y = margin

    // ---- paints ---------------------------------------------------------------

    private fun paint(size: Float, bold: Boolean = false, color: Int = INK): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
            this.color = color
        }

    private val title = paint(20f, bold = true)
    private val subhead = paint(14f)
    private val heading = paint(13f, bold = true)
    private val body = paint(11f)
    private val bodyBold = paint(11f, bold = true)
    private val muted = paint(11f, color = MUTED)
    private val big = paint(26f, bold = true)
    private val good = paint(11f, color = GOOD)
    private val bad = paint(11f, color = BAD)
    private val goodBig = paint(26f, bold = true, color = GOOD)
    private val badBig = paint(26f, bold = true, color = BAD)
    private val mutedBig = paint(26f, bold = true, color = MUTED)
    private val small = paint(8f, color = MUTED)
    private val rule = Paint().apply { color = RULE; strokeWidth = 1f }

    /** A [TextPaint] matching [body], for `TextUtils.ellipsize` on the long name column. */
    private val ellipsizer = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        color = INK
    }

    // ---- public entry point ---------------------------------------------------

    fun write(doc: SemesterRecordDoc) {
        title(doc.title)
        doc.displayName?.let { subhead(it) }
        gap(6f)
        fact("Semester", doc.semesterLabel)
        fact("Term", doc.dateRange)
        fact("Exported", DATE.format(doc.exportedOn))
        doc.section?.let { fact("Section", it) }
        doc.batch?.let { fact("Batch", it) }
        gap(4f)
        ruleLine()

        heading("Overall attendance")
        overallPercent(doc)
        fact("Target", doc.target.displayPercent())
        if (doc.overallUnitsHeld == 0) {
            fact("Hours held", "0")
            fact("Hours attended", "0")
        } else {
            fact("Hours held", doc.overallUnitsHeld.toString())
            fact("Hours attended", doc.overallUnitsAttended.toString())
        }
        gap(4f)
        ruleLine()

        heading("Courses")
        if (doc.courses.isEmpty()) {
            body("No courses with recorded activity this semester.")
        } else {
            courseTable(doc.courses)
        }
        gap(6f)

        if (doc.sessions.isNotEmpty()) {
            ruleLine()
            heading("Session history")
            sessionTable(doc.sessions)
        }
    }

    override fun close() {
        // The last page is finished here; every earlier page was finished by [newPage].
        drawFooter()
        document.finishPage(page)
    }

    // ---- blocks ---------------------------------------------------------------

    private fun overallPercent(doc: SemesterRecordDoc) {
        val percent = doc.overallPercent
        val text = percent.displayPercent()
        val p = when {
            percent == null -> mutedBig
            percent >= doc.target -> goodBig
            else -> badBig
        }
        val lh = p.lineHeight()
        ensureSpace(lh)
        canvas.drawText(text, margin, y - p.ascent(), p)
        y += lh
        gap(2f)
    }

    /** Course table: Code | Course | Held | Att. | % | Target, with the % cell coloured by target status. */
    private fun courseTable(courses: List<CourseRow>) {
        courseHeader()
        courses.forEach { row ->
            if (y + COURSE_ROW > pageHeight - margin) { newPage(); courseHeader() }
            val baseline = y + (COURSE_ROW - body.lineHeight()) / 2f - body.ascent()
            canvas.drawText(row.code, COL_CODE, baseline, bodyBold)
            canvas.drawText(ellipsize(row.name, COL_NAME_WIDTH), COL_NAME, baseline, body)
            drawRight(row.unitsHeld.toString(), COL_HELD_R, baseline, body)
            drawRight(row.unitsAttended.toString(), COL_ATT_R, baseline, body)
            val pctText = row.percent.displayPercent()
            val pctPaint = if (row.meetsTarget) good else bad
            drawRight(pctText, COL_PCT_R, baseline, pctPaint)
            drawRight(row.target.displayPercent(), COL_TGT_R, baseline, muted)
            y += COURSE_ROW
        }
    }

    private fun courseHeader() {
        ensureSpace(COURSE_HEADER + 4f)
        val baseline = y + (COURSE_HEADER - muted.lineHeight()) / 2f - muted.ascent()
        canvas.drawText("Code", COL_CODE, baseline, muted)
        canvas.drawText("Course", COL_NAME, baseline, muted)
        drawRight("Held", COL_HELD_R, baseline, muted)
        drawRight("Att.", COL_ATT_R, baseline, muted)
        drawRight("%", COL_PCT_R, baseline, muted)
        drawRight("Target", COL_TGT_R, baseline, muted)
        y += COURSE_HEADER
        canvas.drawLine(margin, y, margin + contentWidth, y, rule)
        y += 4f
    }

    /** Session appendix: Date | Time | Course | Hrs | Att. | Status. */
    private fun sessionTable(sessions: List<SessionRow>) {
        sessionHeader()
        sessions.forEach { row ->
            if (y + SESSION_ROW > pageHeight - margin) { newPage(); sessionHeader() }
            val baseline = y + (SESSION_ROW - body.lineHeight()) / 2f - body.ascent()
            canvas.drawText(DATE.format(row.date), S_DATE, baseline, body)
            canvas.drawText(String.format(Locale.ROOT, "%02d:00", row.startHour), S_TIME, baseline, body)
            canvas.drawText(ellipsize(row.courseCode, S_COURSE_WIDTH), S_COURSE, baseline, body)
            drawRight(row.unitsPlanned.toString(), S_HRS_R, baseline, body)
            drawRight(row.unitsAttended.toString(), S_ATT_R, baseline, body)
            canvas.drawText(row.statusLabel, S_STATUS, baseline, muted)
            y += SESSION_ROW
        }
    }

    private fun sessionHeader() {
        ensureSpace(SESSION_HEADER + 4f)
        val baseline = y + (SESSION_HEADER - muted.lineHeight()) / 2f - muted.ascent()
        canvas.drawText("Date", S_DATE, baseline, muted)
        canvas.drawText("Time", S_TIME, baseline, muted)
        canvas.drawText("Course", S_COURSE, baseline, muted)
        drawRight("Hrs", S_HRS_R, baseline, muted)
        drawRight("Att.", S_ATT_R, baseline, muted)
        canvas.drawText("Status", S_STATUS, baseline, muted)
        y += SESSION_HEADER
        canvas.drawLine(margin, y, margin + contentWidth, y, rule)
        y += 4f
    }

    // ---- primitives ------------------------------------------------------------

    private fun title(text: String) {
        val lh = title.lineHeight()
        ensureSpace(lh)
        canvas.drawText(text, margin, y - title.ascent(), title)
        y += lh
    }

    private fun subhead(text: String) {
        val lh = subhead.lineHeight()
        ensureSpace(lh)
        canvas.drawText(text, margin, y - subhead.ascent(), subhead)
        y += lh
    }

    private fun heading(text: String) {
        gap(4f)
        val lh = heading.lineHeight()
        ensureSpace(lh)
        canvas.drawText(text, margin, y - heading.ascent(), heading)
        y += lh
        gap(2f)
    }

    private fun body(text: String) {
        val lh = body.lineHeight()
        ensureSpace(lh)
        canvas.drawText(text, margin, y - body.ascent(), body)
        y += lh
    }

    /** A label on the left, a value flush right — the shape of every header line. */
    private fun fact(label: String, value: String) {
        val lh = body.lineHeight()
        ensureSpace(lh)
        val baseline = y - body.ascent()
        canvas.drawText(label, margin, baseline, muted)
        drawRight(value, margin + contentWidth, baseline, body)
        y += lh
    }

    private fun drawRight(text: String, rightX: Float, baseline: Float, paint: Paint) {
        canvas.drawText(text, rightX - paint.measureText(text), baseline, paint)
    }

    private fun ruleLine() {
        ensureSpace(6f)
        canvas.drawLine(margin, y, margin + contentWidth, y, rule)
        y += 8f
    }

    private fun gap(pts: Float) {
        y += pts
    }

    private fun ensureSpace(needed: Float) {
        if (y + needed > pageHeight - margin) newPage()
    }

    private fun newPage() {
        drawFooter()
        document.finishPage(page)
        pageNumber++
        pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        page = document.startPage(pageInfo)
        canvas = page.canvas
        y = margin
    }

    private fun drawFooter() {
        val text = "Attendo · page $pageNumber"
        canvas.drawText(
            text,
            margin + contentWidth - small.measureText(text),
            pageHeight - 20f,
            small,
        )
    }

    private fun ellipsize(text: String, maxWidth: Float): String =
        TextUtils.ellipsize(text, ellipsizer, maxWidth, TextUtils.TruncateAt.END)?.toString() ?: text

    private fun Paint.lineHeight(): Float = descent() - ascent()

    /** "75%", or an em dash when nothing has been held — the same convention as the dashboard. */
    private fun Percent?.displayPercent(): String = this?.let { "${it.format()}%" } ?: "—"

    private companion object {
        // Colours. Black ink for text, gray for labels and rules, green/red for the target signal.
        const val INK = 0xFF1A1A1A.toInt()
        const val MUTED = 0xFF5F6368.toInt()
        const val RULE = 0xFFDADCE0.toInt()
        const val GOOD = 0xFF1B7A3E.toInt()
        const val BAD = 0xFFB3261E.toInt()

        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

        // Course-table column geometry, measured from the left margin. Widths sum to 515.
        // code 55 · name 275 · held 50 · att. 55 · % 40 · target 40.
        const val COL_CODE = 0f
        const val COL_NAME = 55f
        const val COL_NAME_WIDTH = 275f
        const val COL_HELD_R = 380f   // right edge of the Held column
        const val COL_ATT_R = 435f    // right edge of Att.
        const val COL_PCT_R = 475f    // right edge of %
        const val COL_TGT_R = 515f    // right edge of Target

        // Session-table column geometry. date 90 · time 60 · course 110 · hrs 65 · att. 65 · status 125.
        const val S_DATE = 0f
        const val S_TIME = 90f
        const val S_COURSE = 150f
        const val S_COURSE_WIDTH = 110f
        const val S_HRS_R = 325f
        const val S_ATT_R = 390f
        const val S_STATUS = 390f

        const val COURSE_ROW = 17f
        const val COURSE_HEADER = 16f
        const val SESSION_ROW = 16f
        const val SESSION_HEADER = 16f
    }
}
