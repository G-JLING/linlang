package api.linlang.banner;
public record BannerOptions(
        String initials,      // 牌头 ASCII 字母，如 "MP"
        String teamZh, String teamEn,
        String seriesZh, String seriesEn,
        String pluginZh, String pluginEn, String version,
        String developer, String site
) {
    public static Builder builder(){ return new Builder(); }
    public static final class Builder {
        private String initials="MP", teamZh="妙控动力", teamEn="MagicPowered";
        private String seriesZh="插件系列", seriesEn="PluginSeries";
        private String pluginZh="插件全名", pluginEn="PluginName", version="v1.0.0.0-SNAPSHOT";
        private String developer="JLING", site="https://magicpowered.cn";
        public Builder initials(String v){ initials=v; return this; }
        public Builder team(String zh,String en){ teamZh=zh; teamEn=en; return this; }
        public Builder series(String zh,String en){ seriesZh=zh; seriesEn=en; return this; }
        public Builder plugin(String zh,String en,String ver){ pluginZh=zh; pluginEn=en; version=ver; return this; }
        public Builder developer(String v){ developer=v; return this; }
        public Builder site(String v){ site=v; return this; }
        public BannerOptions build(){ return new BannerOptions(initials,teamZh,teamEn,seriesZh,seriesEn,pluginZh,pluginEn,version,developer,site); }
    }
}