package me.jling.plugin.config;

import api.linlang.file.file.annotations.ConfigFile;
import api.linlang.file.file.annotations.Comment;

/**
 * Linlang Bukkit 运行时配置。
 */
@ConfigFile
public final class RuntimeConfig {

    public FileService fileService = new FileService();
    public Compatibility compatibility = new Compatibility();
    public volatile GameText gameText = new GameText();

    public static final class FileService {
        public boolean autoRepairMissingKeys = false;
    }

    public static final class Compatibility {
        public boolean warnOnCompatibleVersionDifference = true;
    }

    public static final class GameText {
        @Comment({
                "启用后，具备 linlang.text.chat 权限的玩家可在聊天中使用点击、悬浮等交互描述符。",
                "Paper 优先使用组件聊天事件；Bukkit 对组件消息接管投递，可能影响聊天签名与日志。"
        })
        public volatile boolean chat = false;
        @Comment({
                "Paper 提供命令装饰事件时直接修改原生消息组件；其他平台接管原版 /say 并以组件广播。",
                "Bukkit 接管会绕过原命令的执行、签名、审计及其他插件对该命令的处理；请确认风险后再开启。",
                "兼容路径中的其他文本命令仅改写消息参数，不保证保留颜色或交互效果。"
        })
        public volatile boolean command = false;
        public volatile boolean book = false;
        public volatile boolean anvil = false;
        public volatile boolean sign = false;
        public volatile boolean join = false;
        public volatile boolean quit = false;
        public volatile boolean death = false;
        public volatile boolean advancement = false;
        public volatile boolean kick = false;
        public volatile boolean broadcast = false;
        public volatile boolean motd = false;
        @Comment("在物品打开、拾取和编辑后解析名称与 Lore；实际修改物品并保存原始文本。")
        public volatile boolean item = false;
        public volatile boolean entity = false;
        public volatile boolean textDisplay = false;
        @Comment({
                "允许解析没有玩家来源的广播、MOTD、实体生成与区块加载文本。",
                "仍须开启对应位置开关，且控制台须拥有该位置的 linlang.text.* 权限。"
        })
        public volatile boolean allowServerSources = false;
    }
}
