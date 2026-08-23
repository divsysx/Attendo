package com.attendo.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The subject and faculty acronym keys that sit beside the timetable. */
class GlossaryTest {

    private val subjects = Glossary.parse(
        """
        # Subject acronyms
        ${Glossary.HEADER}
        ADEC,Analog and Digital Electronic Circuits
        DE-1,Digital Electronics-I
        EM-I,Electrical Machines I
        EVS-2,Environmental Science: Theory into Practice-II
        NN,Neural Networks
        DIP,Digital Image Processing
        """.trimIndent(),
    )

    private val faculty = Glossary.parse(
        """
        AKT,Prof. A.K. Tandon
        SG,Dr. Shubham Gupta
        RHS,Dr. Rahul Solanki
        """.trimIndent(),
    )

    @Test
    fun `a code resolves to its full name`() {
        assertEquals("Analog and Digital Electronic Circuits", subjects["ADEC"])
        assertEquals(6, subjects.size)
    }

    @Test
    fun `the header and comments are not entries`() {
        assertNull(subjects["code"])
        assertNull(subjects["# Subject acronyms"])
    }

    @Test
    fun `lookup ignores case and surrounding space`() {
        assertEquals("Neural Networks", subjects[" nn "])
        assertEquals("Neural Networks", subjects["Nn"])
    }

    @Test
    fun `an elective's group suffix falls back to the base code`() {
        // The grid prints NN-A and NN-B; the acronym page lists only NN.
        assertEquals("Neural Networks", subjects["NN-A"])
        assertEquals("Neural Networks", subjects["NN-B"])
        assertEquals("Digital Image Processing", subjects["DIP-B"])
    }

    @Test
    fun `a hyphen that belongs to the code is not stripped`() {
        // Trying the exact code first is what keeps these from becoming DE, EM and EVS.
        assertEquals("Digital Electronics-I", subjects["DE-1"])
        assertEquals("Electrical Machines I", subjects["EM-I"])
        assertEquals("Environmental Science: Theory into Practice-II", subjects["EVS-2"])
    }

    @Test
    fun `an unknown code is null but never blank on screen`() {
        assertNull(subjects["SFL"])
        assertEquals("SFL", subjects.nameOf("SFL"))
        assertEquals("", subjects.nameOf(null))
        assertEquals("", subjects.nameOf("  "))
    }

    @Test
    fun `a shared teaching cell resolves both teachers`() {
        assertEquals(
            listOf("Prof. A.K. Tandon", "Dr. Shubham Gupta"),
            faculty.namesOf("AKT / SG"),
        )
        assertEquals(listOf("Prof. A.K. Tandon", "Dr. Shubham Gupta"), faculty.namesOf("AKT/SG"))
        assertEquals("Prof. A.K. Tandon & Dr. Shubham Gupta", faculty.label("AKT / SG"))
    }

    @Test
    fun `a single teacher needs no ampersand`() {
        assertEquals("Dr. Rahul Solanki", faculty.label("RHS"))
        assertEquals(listOf("Dr. Rahul Solanki"), faculty.namesOf("RHS"))
    }

    @Test
    fun `a missing faculty cell yields nothing rather than an empty name`() {
        assertEquals(emptyList<String>(), faculty.namesOf(null))
        assertEquals(emptyList<String>(), faculty.namesOf(" "))
        assertEquals("", faculty.label(null))
    }

    @Test
    fun `an unresolved half of a pair keeps its code`() {
        assertEquals("Prof. A.K. Tandon & ZZ", faculty.label("AKT / ZZ"))
    }

    @Test
    fun `a half-typed line costs that line only`() {
        val glossary = Glossary.parse(
            """
            ADEC,Analog and Digital Electronic Circuits
            TOC
            ,Theory of Computation
            NN,
            CN,Computer Networks
            """.trimIndent(),
        )

        assertEquals(2, glossary.size)
        assertEquals("Computer Networks", glossary["CN"])
        assertNull(glossary["TOC"])
    }

    @Test
    fun `a name containing a comma survives quoting`() {
        val glossary = Glossary.parse("""DASIC,"Digital ASIC Design: RTL, GDS-II"""")

        assertEquals("Digital ASIC Design: RTL, GDS-II", glossary["DASIC"])
    }

    @Test
    fun `a repeated code keeps the first spelling`() {
        val glossary = Glossary.parse("SY,Dr. Sangeeta Yadav\nSY,Dr. Sanjeev Yadav")

        assertEquals("Dr. Sangeeta Yadav", glossary["SY"])
    }

    @Test
    fun `an empty key is usable and empty`() {
        assertTrue(Glossary.parse("").isEmpty)
        assertTrue(Glossary.EMPTY.isEmpty)
        assertEquals("ADEC", Glossary.EMPTY.nameOf("ADEC"))
    }
}
