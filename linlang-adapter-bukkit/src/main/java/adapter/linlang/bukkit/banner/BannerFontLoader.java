package adapter.linlang.bukkit.banner;

import api.linlang.banner.AsciiFont;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class BannerFontLoader {
    private BannerFontLoader(){}

    @SuppressWarnings("unchecked")
    public static AsciiFont loadFromResource(String resourceName){
        try (InputStream in = BannerFontLoader.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) throw new IllegalArgumentException("Font resource not found: "+resourceName);
            Yaml yaml = new Yaml();
            Map<String,Object> root = yaml.load(in);

            int height = (int) root.getOrDefault("height", 5);
            int gap = (int) root.getOrDefault("gap", 1);
            Map<String,Object> glyphs = (Map<String,Object>) root.get("glyphs");

            var b = AsciiFont.builder().height(height).gap(gap);
            for (var e : glyphs.entrySet()){
                char ch = e.getKey().charAt(0);
                List<String> lines = (List<String>) e.getValue();
                b.put(ch, lines);
            }
            return b.build();
        } catch (Exception e){
            throw new RuntimeException("[linlang] Failed to load ASCII font", e);
        }
    }
}