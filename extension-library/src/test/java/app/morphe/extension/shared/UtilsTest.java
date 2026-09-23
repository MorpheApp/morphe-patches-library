package app.morphe.extension.shared;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class UtilsTest {

    @Test
    public void testIndexOf() {
        assertEquals(0, Utils.indexOf("hello world", "hello"));
        assertEquals(6, Utils.indexOf("hello world", "world"));
        assertEquals(-1, Utils.indexOf("hello world", "foo"));
        assertEquals(0, Utils.indexOf("hello world", ""));
    }

    @Test
    public void testIndexOfFromIndex() {
        assertEquals(0, Utils.indexOf("hello world", "hello", 0));
        assertEquals(-1, Utils.indexOf("hello world", "hello", 1));
        assertEquals(6, Utils.indexOf("hello world world", "world", 0));
        assertEquals(6, Utils.indexOf("hello world world", "world", 6));
        assertEquals(12, Utils.indexOf("hello world world", "world", 7));
        assertEquals(-1, Utils.indexOf("hello world world", "world", 13));

        // Negative fromIndex
        assertEquals(0, Utils.indexOf("hello world", "hello", -5));

        // Empty pattern
        assertEquals(0, Utils.indexOf("hello", "", 0));
        assertEquals(2, Utils.indexOf("hello", "", 2));
        assertEquals(5, Utils.indexOf("hello", "", 5));
        assertEquals(5, Utils.indexOf("hello", "", 10));

        // Pattern longer than remaining string
        assertEquals(-1, Utils.indexOf("hello", "longpattern", 0));
        assertEquals(-1, Utils.indexOf("hello", "world", 10));

        // CharSequence types (e.g. StringBuilder, Spanned)
        StringBuilder sb = new StringBuilder("abc abc");
        assertEquals(0, Utils.indexOf(sb, "abc", 0));
        assertEquals(4, Utils.indexOf(sb, "abc", 1));
    }
}
