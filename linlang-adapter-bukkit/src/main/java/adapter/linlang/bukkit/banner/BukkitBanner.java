// adapter/linlang/bukkit/banner/BukkitBanner.java
package adapter.linlang.bukkit.banner;

import api.linlang.banner.*;
import api.linlang.audit.called.LinLogs;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class BukkitBanner {

    private BukkitBanner(){}

    /** 通过 Bukkit Logger 输出 */
    public static void printWithBukkit(JavaPlugin plugin, AsciiFont font, BannerOptions opt){
        BannerRenderer.print(font, opt, line -> plugin.getLogger().info(line));
    }

    /** 通过 Linlogs 输出*/
    public static void printWithLogs(AsciiFont font, BannerOptions opt){
        BannerRenderer.print(font, opt, line -> LinLogs.info(line));
    }
}