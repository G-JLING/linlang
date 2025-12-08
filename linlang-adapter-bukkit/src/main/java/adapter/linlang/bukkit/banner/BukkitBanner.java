package adapter.linlang.bukkit.banner;

import api.linlang.audit.LinLog;
import api.linlang.banner.*;
import api.linlang.banner.service.AsciiFont;
import api.linlang.banner.service.BannerRenderer;
import org.bukkit.plugin.java.JavaPlugin;

public final class BukkitBanner{

    private BukkitBanner() {} // 工具类不允许实例化

    private static AsciiFont resolveFont() {
        // 优先取默认字体（runtime 启动时建议先 loadBuiltin()）
        AsciiFont f = BannerFontLoader.getDefaultFont();
        if (f != null) return f;

        // 尝试按资源名直接加载（向后兼容）
        f = BannerFontLoader.font("banner/font.yml");
        if (f != null) return f;

        // 都没有：记录诊断日志并返回 null
        try {
            LinLog.error("[linbanner] No ascii font available. Available keys: {}", null, String.join(",", BannerFontLoader.listAvailableNames()));
        } catch (Throwable ignored) {
            LinLog.error("[linbanner] No ascii font available.", ignored);
        }
        return null;
    }

    /** 通过 Bukkit Logger 输出 */
    public static void printWithBukkit(JavaPlugin plugin, BannerOptions opt) {
        AsciiFont font = resolveFont();
        if (font == null) {
            plugin.getLogger().warning("[linbanner] no ascii font; skipping banner print.");
            return;
        }
        BannerRenderer.print(font, opt, line -> plugin.getLogger().info(line));
    }

    /** 通过 Linlog 输出 */
    public static void printWithLogs(BannerOptions opt) {
        AsciiFont font = resolveFont();
        if (font == null) {
            LinLog.warn("[linbanner] no ascii font; skipping banner print.");
            return;
        }
        BannerRenderer.print(font, opt, LinLog::info);
    }
}