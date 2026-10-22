package dev.larryjones.quietboard

import dev.larryjones.quietboard.PersonalInfo.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which of your saved emails and addresses show for the text before the cursor (no phone needed). */
class PersonalInfoTest {
    private val info = PersonalInfo(
        listOf("larry@example.com", "lj@work.example.org"),
        listOf("1234 W Main St, Phoenix, AZ 85001", "PO Box 77\nTempe, AZ 85281"),
    )

    private fun values(before: String, field: Field) = info.matches(before, field).map { it.value }

    @Test fun emailFieldOffersEveryEmailWhenEmpty() {
        assertEquals(listOf("larry@example.com", "lj@work.example.org"), values("", Field.EMAIL))
        assertEquals("the next recipient", listOf("larry@example.com", "lj@work.example.org"), values("a@b.com, ", Field.EMAIL))
        assertTrue("no addresses in an email field", values("", Field.EMAIL).none { it.startsWith("1234") })
    }

    @Test fun emailFieldMatchesWhatYouTyped() {
        val m = info.matches("La", Field.EMAIL)
        assertEquals(listOf("larry@example.com"), m.map { it.value })
        assertEquals("replaces exactly what was typed", "La", m[0].typed)
        assertTrue(m[0].strong)
        assertEquals(listOf("lj@work.example.org"), values("lj@w", Field.EMAIL))
        assertTrue("already typed in full", values("larry@example.com", Field.EMAIL).isEmpty())
        assertTrue("not yours", values("bob", Field.EMAIL).isEmpty())
    }

    @Test fun plainTextNeedsThreeLettersOrAnAt() {
        assertTrue("\"la\" is more likely \"last\"", values("we'll la", Field.OTHER).isEmpty())
        val weak = info.matches("email me at lar", Field.OTHER)
        assertEquals(listOf("larry@example.com"), weak.map { it.value })
        assertFalse("offered on the side", weak[0].strong)
        val sure = info.matches("email me at <larry@", Field.OTHER)
        assertEquals("an @ means it's an email; the bracket isn't replaced", "larry@", sure[0].typed)
        assertTrue(sure[0].strong)
        assertTrue("no list of emails mid-sentence", values("hi ", Field.OTHER).isEmpty())
    }

    @Test fun addressesMatchFromAWordStartAcrossSpaces() {
        val m = info.matches("I live at 1234 W Ma", Field.OTHER)
        assertEquals(listOf("1234 W Main St, Phoenix, AZ 85001"), m.map { it.value })
        assertEquals("1234 W Ma", m[0].typed)
        assertTrue("not from the middle of a word", values("x234 W Ma", Field.OTHER).isEmpty())
        assertTrue("two digits elsewhere is too little", values("at 12", Field.OTHER).isEmpty())
        assertTrue("only the cursor's line", values("1234 W\nMa", Field.OTHER).isEmpty())
    }

    @Test fun addressFieldsOfferEverySavedAddressWhenEmpty() {
        assertEquals(
            "saved on two lines, offered on one",
            listOf("1234 W Main St, Phoenix, AZ 85001", "PO Box 77, Tempe, AZ 85281"), values("", Field.ADDRESS),
        )
        assertEquals(listOf("PO Box 77, Tempe, AZ 85281"), values("po", Field.ADDRESS))
        assertTrue("no emails in an address field", values("", Field.ADDRESS).none { '@' in it })
    }

    @Test fun streetFieldsGetTheStreetLineOnly() {
        assertEquals(listOf("1234 W Main St", "PO Box 77"), values("", Field.STREET))
        assertEquals(listOf("1234 W Main St"), values("1234", Field.STREET))
    }

    @Test fun longestMatchComesFirst() {
        val two = PersonalInfo(emptyList(), listOf("12 Oak Ave", "12 Elm Way"))
        assertEquals(listOf("12 Elm Way"), two.matches("12 E", Field.ADDRESS).map { it.value })
        assertEquals(listOf("12 Oak Ave", "12 Elm Way"), two.matches("12", Field.ADDRESS).map { it.value })
    }

    @Test fun nothingSavedNothingOffered() {
        val none = PersonalInfo(listOf(" ", ""), listOf("\n"))
        assertTrue(none.isEmpty)
        assertTrue(none.matches("", Field.EMAIL).isEmpty())
    }

    @Test fun emailCheck() {
        assertTrue(PersonalInfo.looksLikeEmail("larry@example.com"))
        assertFalse(PersonalInfo.looksLikeEmail("larry@example"))
        assertFalse(PersonalInfo.looksLikeEmail("larry @example.com"))
        assertFalse(PersonalInfo.looksLikeEmail("a@b@c.com"))
    }
}
