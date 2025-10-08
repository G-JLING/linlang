package api.linlang.banner;

import java.util.*;

public final class AsciiFont {
    private final Map<Character, List<String>> glyphs;
    private final int height;
    private final int gap;

    private AsciiFont(Map<Character, List<String>> g, int h, int gap) {
        this.glyphs = g;
        this.height = h;
        this.gap = gap;
    }

    public int height() {
        return height;
    }

    public int gap() {
        return gap;
    }

    public List<String> glyph(char ch) {
        List<String> g = glyphs.get(Character.toUpperCase(ch));
        if (g != null) return g;
        if (Character.isWhitespace(ch)) {
            return java.util.Collections.nCopies(height, "");
        }
        List<String> qm = glyphs.get('?');
        if (qm != null) return qm;
        return java.util.Collections.nCopies(height, "");
    }


    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<Character, List<String>> g = new HashMap<>();
        private int height = 5;
        private int gap = 1;

        public Builder height(int h) {
            this.height = h;
            return this;
        }

        public Builder gap(int gpx) {
            this.gap = gpx;
            return this;
        }

        public Builder put(char c, List<String> lines) {
            g.put(Character.toUpperCase(c), List.copyOf(lines));
            return this;
        }

        public AsciiFont build() {
            return new AsciiFont(Collections.unmodifiableMap(g), height, gap);
        }
    }
}