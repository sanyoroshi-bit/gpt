package jp.tenreader.launcher;

import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class JapaneseWordsTest {
    @Test public void actualDictionaryPreservesPersonalAndOrganizationNames(){
        List<String> words=JapaneseWords.split("夏目漱石と村上春樹が東京大学と日本経済新聞について話す。",Collections.emptyList());
        assertTrue(words.toString(),words.contains("夏目漱石"));assertTrue(words.toString(),words.contains("村上春樹"));assertTrue(words.toString(),words.contains("東京大学"));assertTrue(words.toString(),words.contains("日本経済新聞"));assertTrue(words.toString(),words.contains("が"));
    }
    @Test public void protectedNamesUseLongestMatchWithoutLosingOriginalSurfaces(){
        String text="架空未来研究所と架空未来、OpenAI・ChatGPTを読む。 👨‍👩‍👧‍👦 é";
        List<String> words=JapaneseWords.split(text,JapaneseWords.terms("架空未来\n架空未来研究所\nOpenAI・ChatGPT"));
        assertEquals(text,String.join("",words));assertTrue(words.contains("架空未来研究所"));assertTrue(words.contains("OpenAI・ChatGPT"));
    }
    @Test public void spacesPreventAccidentalNameConcatenation(){String text="東京 大学 研究所 を読む。";List<String> words=JapaneseWords.split(text,Collections.emptyList());assertEquals(text,String.join("",words));assertFalse(words.contains("東京大学研究所"));}
    @Test public void shortRegisteredLatinNamesDoNotSplitLongerWords(){List<String> words=JapaneseWords.split("SAILとOpenAIとAIを読む。",JapaneseWords.terms("AI"));assertEquals("SAILとOpenAIとAIを読む。",String.join("",words));assertTrue(words.toString(),words.contains("SAIL"));assertTrue(words.toString(),words.contains("OpenAI"));assertEquals(1,words.stream().filter("AI"::equals).count());}
    @Test public void katakanaPersonalNamesWithMiddleDotsStayTogether(){String text="ウォーレン・バフェットとジョン・スミスが話す。";List<String> words=JapaneseWords.split(text,Collections.emptyList());assertEquals(text,String.join("",words));assertTrue(words.toString(),words.contains("ウォーレン・バフェット"));assertTrue(words.toString(),words.contains("ジョン・スミス"));assertTrue(words.contains("と"));}
}
