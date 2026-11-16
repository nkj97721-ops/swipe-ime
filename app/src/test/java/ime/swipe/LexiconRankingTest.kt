package ime.swipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconRankingTest {
    @Test fun fuzzyMatchesRankAfterExactOnes() {
        val lex = Lexicon().apply {
            loadRows(sequenceOf(
                "ni\t你\t200",
                "ni\t尼\t100",
                "li\t里\t100000"
            ))
        }
        val texts = lex.candidates("ni").map { it.text }
        val li = texts.indexOf("里")
        assertTrue("fuzzy match should still be offered", li >= 0)
        assertTrue(texts.indexOf("你") in 0 until li)
        assertTrue(texts.indexOf("尼") in 0 until li)
    }

    @Test fun singleLetterShowsCommonCharacterNotAlphabeticallyFirstSyllables() {
        val lex = Lexicon().apply {
            loadRows(sequence {
                for (a in 'a'..'z') for (b in 'a'..'z') yield("wa$a$b\t哇\t1")
                yield("wo\t我\t5000")
            })
        }
        assertTrue(lex.candidates("w").any { it.text == "我" })
    }

    @Test fun veryLongPhraseDoesNotBeatCommonCharacter() {
        val lex = Lexicon().apply {
            loadRows(sequenceOf(
                "shi\t是\t10000000",
                "sanguoqunyingzhuan\t三国群英传\t20000000"
            ))
        }
        assertEquals("是", lex.candidates("s").first().text)
    }
}
