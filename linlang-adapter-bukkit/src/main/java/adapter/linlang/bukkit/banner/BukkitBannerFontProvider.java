package adapter.linlang.bukkit.banner;

import api.linlang.banner.provider.BannerFontProvider;
import api.linlang.banner.service.AsciiFont;
import core.linlang.banner.BannerFontLoader;

/**
 * Bukkit 运行时的 ASCII 字体提供者。
 *
 * <p>通过 {@link BannerFontLoader} 从资源中加载字体，并提供给 {@link api.linlang.banner.LinBanner} 使用。</p>
 */
public final class BukkitBannerFontProvider implements BannerFontProvider {

    @Override
    public AsciiFont font() {

        AsciiFont f = BannerFontLoader.getDefaultFont();
        if (f != null) return f;


        if (BannerFontLoader.loadBuiltin()) {
            return BannerFontLoader.getDefaultFont();
        }

        return BannerFontLoader.font("banner/font.yml");
    }
}