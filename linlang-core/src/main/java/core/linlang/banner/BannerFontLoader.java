package core.linlang.banner;

import api.linlang.banner.service.AsciiFont;
import api.linlang.audit.LinLog;
import api.linlang.audit.problem.LinProblem;
import core.linlang.audit.problem.BuiltinProblemCatalog;
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
        AsciiFont cached = REG.get(resourceName);
        if (cached != null) return cached;

        InputStream in = null;

        // 1) 优先使用调用方传入的 ClassLoader
        ClassLoader cl = (classLoader != null ? classLoader : BannerFontLoader.class.getClassLoader());
        in = cl.getResourceAsStream(resourceName);

        // 2) 若失败，尝试线程上下文 ClassLoader
        if (in == null) {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            if (ctx != null && ctx != cl) {
                in = ctx.getResourceAsStream(resourceName);
            }
        }

        // 3) 若仍失败，尝试系统 ClassLoader（通常没必要，但作为兜底）
        if (in == null) {
            ClassLoader sys = ClassLoader.getSystemClassLoader();
            if (sys != null && sys != cl) {
                in = sys.getResourceAsStream(resourceName);
            }
        }

        if (in == null) {
            // 到这里说明在所有尝试过的 ClassLoader 中都找不到该资源
            // 可以在调用方（如 BukkitBanner）增加一条更详细的 warn 日志，
            // 包含当前 classpath / 已加载 jar 等信息。
            return null;
        }

        try (InputStream use = in) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(use);
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
            LinLog.problem(LinProblem.of(
                    BuiltinProblemCatalog.BANNER_FONT_LOAD_FAILED,
                    e,
                    "resource", resourceName
            ));
            return null;
        }
    }

    /**
     * 使用 BannerFontLoader 自身的 classloader 加载资源（通常是 core 模块自带的内置字体）。
     */
    public static AsciiFont font(String resourceName) {
        return font(resourceName, BannerFontLoader.class.getClassLoader());
    }

    /**
     * 载入内置字体到 registry，返回是否成功。
     *
     * <p>会尝试从多个 ClassLoader 中查找 {@code banner/font.yml}，以提高在多模块 /
     * 多插件环境下的可用性。</p>
     */
    public static boolean loadBuiltin() {
        // 1) 先尝试线程上下文 ClassLoader（通常是插件的 ClassLoader）
        AsciiFont f = font("banner/font.yml", Thread.currentThread().getContextClassLoader());

        // 2) 若失败，再尝试 BannerFontLoader 自己的 ClassLoader
        if (f == null) {
            f = font("banner/font.yml", BannerFontLoader.class.getClassLoader());
        }

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
