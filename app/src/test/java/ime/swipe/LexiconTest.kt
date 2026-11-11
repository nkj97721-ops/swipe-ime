package ime.swipe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconTest {
    private fun sample(): Lexicon = Lexicon().apply {
        loadRows(sequenceOf(
            "bei\t北\t80",
            "beijing\t北京\t500",
            "hao\t好\t100",
            "jing\t京\t80",
            "ni\t你\t200",
            "nihao\t你好\t600",
            "zhang\t张\t300",
            "zhhang\t误匹配\t900",
            "zhong\t中\t200",
            "zhongguo\t中国\t700"
        ))
    }

    @Test fun exactPinyinReturnsPhrase() {
        assertTrue(sample().candidates("nihao").any { it.text == "你好" })
    }

    @Test fun fuzzyInitialExpansionFindsRetroflex() {
        assertTrue(sample().candidates("zang").any { it.text == "张" })
    }

    @Test fun initialsFindMultiSyllablePhrase() {
        assertTrue(sample().candidates("bj").any { it.text == "北京" })
    }

    @Test fun alreadyRetroflexInputDoesNotCreateDoubleHVariant() {
        assertFalse(sample().candidates("zhang").any { it.text == "误匹配" })
    }

    @Test fun uppercaseInputIsNormalized() {
        assertTrue(sample().candidates("NIHAO").any { it.text == "你好" })
    }

    @Test fun unknownInputDoesNotReturnUnrelatedPhrase() {
        assertFalse(sample().candidates("qqqq").any { it.text == "你好" })
    }
}
