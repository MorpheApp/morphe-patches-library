package app.morphe.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class StringTrieSearchTest {

    @Test
    public void constructorsAcceptStringPatterns() {
        new StringTrieSearch();
        new StringTrieSearch("a", "b");
    }

    @Test
    public void stringAndBuilderProduceTheSameCallbackTrace() {
        compare("ab", false, "a", "ab");
        compare("ab", true, "a", "ab");
        compare("abc", false, "ab", "bc", "b");
        compare("foo bar foo", false, "foo");
        compare("foo bar foo", true, "foo");
        compare("toggle_button.e", false, "button.e", "toggle_button.e");
        compare("toggle_button.e", true, "toggle_button.e", "button.e");
    }

    private static void compare(String value, boolean firstPatternAccepts, String... patterns) {
        String searchedString = value;
        StringBuilder searchedBuilder = new StringBuilder(value);
        List<String> stringTrace = new ArrayList<>();
        List<String> builderTrace = new ArrayList<>();
        CharSequence[] stringSeen = new CharSequence[1];
        CharSequence[] builderSeen = new CharSequence[1];

        search(searchedString, stringTrace, stringSeen, firstPatternAccepts, patterns);
        search(searchedBuilder, builderTrace, builderSeen, firstPatternAccepts, patterns);

        assertEquals(stringTrace, builderTrace);
        assertEquals(true, !stringTrace.isEmpty());
        assertSame(searchedString, stringSeen[0]);
        assertSame(searchedBuilder, builderSeen[0]);
    }

    private static void search(CharSequence text, List<String> trace, CharSequence[] seen,
                               boolean firstPatternAccepts, String... patterns) {
        StringTrieSearch trie = new StringTrieSearch();
        for (int i = 0; i < patterns.length; i++) {
            String pattern = patterns[i];
            boolean accept = firstPatternAccepts && i == 0;
            trie.addPattern(pattern, (textSearched, index, length, parameter) -> {
                seen[0] = textSearched;
                trace.add(pattern + "@" + index + ":" + length + ":" + accept);
                return accept;
            });
        }
        trie.matches(text, "callback");
    }
}
