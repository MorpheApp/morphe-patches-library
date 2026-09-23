package app.morphe.extension.shared;

/**
 * Text pattern searching using a prefix tree (trie).
 */
@SuppressWarnings("unused")
public final class StringTrieSearch extends TrieSearch<CharSequence> {

    private static final class StringTrieNode extends TrieNode<CharSequence> {
        StringTrieNode() {
            super();
        }
        StringTrieNode(char nodeCharacterValue) {
            super(nodeCharacterValue);
        }
        @Override
        TrieNode<CharSequence> createNode(char nodeValue) {
            return new StringTrieNode(nodeValue);
        }
        @Override
        char getCharValue(CharSequence text, int index) {
            return text.charAt(index);
        }
        @Override
        int getTextLength(CharSequence text) {
            return text.length();
        }
    }

    public StringTrieSearch(String... patterns) {
        super(new StringTrieNode(), patterns);
    }
}
