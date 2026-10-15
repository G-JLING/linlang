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
                "交互消息会接管 Bukkit 聊天事件并以组件重新投递；可能绕过其他插件的聊天签名、日志或后续处理。",
                "普通颜色消息仍由原聊天事件发送。请确认服务器的聊天管理规则后再开启。"
        })
        public volatile boolean chat = false;
        @Comment({
                "启用后，原版 /say 的高级字符串由琳琅接管并以组件广播。",
                "这会绕过原命令的执行、签名、审计及其他插件对该命令的处理；请确认风险后再开启。",
                "其他支持的文本命令仅改写消息参数，不保证目标命令保留颜色或交互效果。"
        })
        public volatile boolean command = false;
        public volatile boolean book = false;
        public volatile boolean anvil = false;
        public volatile boolean sign = false;
    }
}
