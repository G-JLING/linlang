package core.linlang.banner;

import api.linlang.banner.service.AsciiFont;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用 ASCII 字体加载器：负责从 YAML 资源加载 {@link AsciiFont}。
 *
 * <p>支持缓存与内置字体，适用于任意运行时（不依赖 Bukkit）。</p>
 */
public final class BannerFontLoader {

    // 注册表：resourceName -> AsciiFont
    private static final Map<String, AsciiFont> REG = new ConcurrentHashMap<>();
    private static final String DEFAULT_KEY = "builtin";

    private BannerFontLoader() {}

    /**
     * 从指定 classloader 的资源加载字体；若尚未加载则尝试加载 resourceName 指向的资源；
     * 失败时返回 null（不抛出）。
     */
    @SuppressWarnings("unchecked")
    public static AsciiFont font(String resourceName, ClassLoader classLoader) {
        AsciiFont f = REG.get(resourceName);
        if (f != null) return f;

        ClassLoader cl = (classLoader != null ? classLoader : BannerFontLoader.class.getClassLoader());
        try (InputStream in = cl.getResourceAsStream(resourceName)) {
            if (in == null) return null;
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            int height = asInt(root.getOrDefault("height", 5));
            int gap    = asInt(root.getOrDefault("gap", 1));
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

    /** 使用 BannerFontLoader 自身的 classloader 加载资源（通常是 core 模块自带的内置字体） */
    public static AsciiFont font(String resourceName) {
        return font(resourceName, BannerFontLoader.class.getClassLoader());
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

    private static int asInt(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception ignored) {
            return 0;
        }
    }
}