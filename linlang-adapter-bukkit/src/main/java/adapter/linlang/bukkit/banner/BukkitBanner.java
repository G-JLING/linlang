package adapter.linlang.bukkit.banner;


import api.linlang.audit.LinLog;
import api.linlang.banner.BannerOptions;
import api.linlang.banner.LinBanner;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit 下的铭牌打印工具。
 *
 * <p>内部委托给 {@link LinBanner}，只是输出目标换成 Bukkit Logger。</p>
 */
public final class BukkitBanner {

    private BukkitBanner() {}

    /** 通过 Bukkit Logger 输出 */
    public static void printWithBukkit(JavaPlugin plugin, BannerOptions opt) {
        LinBanner.print(line -> plugin.getLogger().info(line), opt);
    }

    /** 通过 LinLog 输出 */
    public static void printWithLogs(BannerOptions opt) {
        LinBanner.printWithLogs(opt);
    }
}