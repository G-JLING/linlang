package adapter.linlang.bukkit.banner;

import api.linlang.banner.service.AsciiFont;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class BannerFontLoader {

    // 注册表：resourceName -> AsciiFont
    private static final Map<String, AsciiFont> REG = new ConcurrentHashMap<>();
    private static final String DEFAULT_KEY = "builtin";

    private BannerFontLoader() {}

    /** 立即尝试返回字体；若尚未加载则尝试加载 resourceName 指向的资源；失败时返回 null（不抛） */
    @SuppressWarnings("unchecked")
    public static AsciiFont font(String resourceName) {
        AsciiFont f = REG.get(resourceName);
        if (f != null) return f;

        try (InputStream in = BannerFontLoader.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (in == null) return null;
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            int height = (int) ((root.getOrDefault("height", 5)) instanceof Number ? ((Number) root.getOrDefault("height", 5)).intValue() : Integer.parseInt(String.valueOf(root.getOrDefault("height", 5))));
            int gap = (int) ((root.getOrDefault("gap", 1)) instanceof Number ? ((Number) root.getOrDefault("gap", 1)).intValue() : Integer.parseInt(String.valueOf(root.getOrDefault("gap", 1))));
            Map<String, Object> glyphs = (Map<String, Object>) root.get("glyphs");

            var b = AsciiFont.builder().height(height).gap(gap);
            if (glyphs != null) {
                for (var e : glyphs.entrySet()) {
                    char ch = e.getKey().toString().charAt(0);
                    List<String> lines = (List<String>) e.getValue();
                    b.put(ch, List.copyOf(lines));
                }
            }
            AsciiFont built = b.build();
            REG.put(resourceName, built);
            return built;
        } catch (Exception e) {
            // 不抛出，记录日志由调用方负责；返回 null 以供回退
            return null;
        }
    }

    /** 载入内置字体到 registry，返回是否成功 */
    public static boolean loadBuiltin() {
        AsciiFont f = font("banner/font.yml");
        if (f != null) {
            REG.put(DEFAULT_KEY, f);
            return true;
        }
        return false;
    }

    /** 返回默认字体（若 loadBuiltin 上次成功） */
    public static AsciiFont getDefaultFont() {
        return REG.get(DEFAULT_KEY);
    }

    /** 列出已加载的 resource keys（供诊断用） */
    public static List<String> listAvailableNames() {
        return new ArrayList<>(REG.keySet());
    }

    /** 清空 registry（测试用） */
    public static void clear() {
        REG.clear();
    }
}