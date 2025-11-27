// adapter/linlang/bukkit/banner/BukkitBanner.java
package adapter.linlang.bukkit.banner;

import api.linlang.audit.LinLog;
import api.linlang.banner.*;
import api.linlang.banner.service.AsciiFont;
import api.linlang.banner.service.BannerOptions;
import api.linlang.banner.service.BannerRenderer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class BukkitBanner{

    private static final AsciiFont asciiFont;

    static {
        asciiFont = BannerFontLoader.font("banner/font.yml");
    }

    private BukkitBanner() {} // 工具类不允许实例化

    /** 通过 Bukkit Logger 输出 */
    public static void printWithBukkit(JavaPlugin plugin, BannerOptions opt) {
        BannerRenderer.print(asciiFont, opt, line -> plugin.getLogger().info(line));
    }

    /** 通过 Linlog 输出 */
    public static void printWithLogs(BannerOptions opt) {
        BannerRenderer.print(asciiFont, opt, LinLog::info);
    }
}