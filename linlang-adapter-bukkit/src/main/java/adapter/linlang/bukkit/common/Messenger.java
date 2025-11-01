package adapter.linlang.bukkit.common;

/*
 * Messenger：统一向玩家发送消息。
 * 约定：占位符使用 {key}，支持可变参数或 Map。
 * 颜色：将 & 转为 §；1.16+ 支持 Hex。
 */

import adapter.linlang.bukkit.file.common.file.VersionDetector;
import api.linlang.file.service.LangService;
import core.linlang.file.text.ColorCodes;
import core.linlang.file.text.Placeholders;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 面向 Bukkit 的消息发送门面。
 * <p>
 * 功能：
 * <ul>
 *   <li>传入消息字符串或字符串形式的键</li>
 *   <li>占位符语法：{@code {placeholder}, "message"}，可变参数或 Map 传参；</li>
 *   <li>颜色转换：将 {@code &} 转为 {@code §}，在 1.16+ 支持 HEX；</li>
 *   <li>提供 Chat、Title、ActionBar 三种输出通道。</li>
 * </ul>
 */
public final class Messenger {
    private final Function<String, String> translator; // key -> template
    private final boolean hexColor; // 1.16+ -> true

    // Optional prefix (e.g., plugin prefix). Defaults to empty.
    private Supplier<String> prefixSupplier = () -> "";

    /**
     * 使用 {@link LangService} 作为翻译源的构造器。
     * <p>等价于传入 {@code translator = message::tr}。</p>
     *
     * @param lang 语言服务实例，用于根据消息键解析模板；不可为 {@code null}
     */
    public Messenger(LangService lang) {
        this(lang::tr);
    }

    /**
     * 使用自定义翻译函数的构造器。
     *
     * @param translator 翻译函数，输入消息键输出模板文本；若为 {@code null} 则退化为恒等（直接回显键）
     */
    public Messenger(Function<String, String> translator) {
        this.translator = translator != null ? translator : (k -> k);
        this.hexColor = isAtLeast116();
    }

    /**
     * Set a fixed text prefix for chat/console messages (not applied to Title/ActionBar).
     */
    public Messenger withPrefix(String prefix) {
        this.prefixSupplier = () -> (prefix == null ? "" : prefix);
        return this;
    }

    /**
     * Set a dynamic prefix provider (e.g., depends on runtime state).
     */
    public Messenger withPrefixProvider(Supplier<String> supplier) {
        this.prefixSupplier = (supplier == null ? () -> "" : supplier);
        return this;
    }

    /* 四个基本方法 */

    /**
     * 1) 直接发送可显示文本（占位符：可变参数）。
     * <p>示例：{@code sendText(player, "&7Hello {name}", "name", p.getName())}</p>
     *
     * @param player   目标玩家
     * @param template 可显示文本模板，允许包含 {@code {key}} 占位符与 {@code &} 颜色码
     * @param kv       以 {@code key, value, key, value...} 形式给定的占位符参数
     */
    public void sendText(Player player, String template, Object... kv) {
        sendText((CommandSender) player, template, kv);
    }

    /**
     * 1a) 直接发送可显示文本到任意 CommandSender（占位符：可变参数）。
     */
    public void sendText(CommandSender sender, String template, Object... kv) {
        sendText(sender, template, Vars.of(kv));
    }

    /**
     * 2) 直接发送可显示文本（占位符：Map）。
     *
     * @param player      目标玩家
     * @param template   可显示文本模板，允许包含 {@code {key}} 占位符与 {@code &} 颜色码
     * @param vars       占位符参数映射，{@code key -> value}
     */
    public void sendText(Player player, String template, Map<String, ?> vars) {
        sendText((CommandSender) player, template, vars);
    }

    /**
     * 2a) 直接发送可显示文本到任意 CommandSender（占位符：Map）。
     */
    public void sendText(CommandSender sender, String template, Map<String, ?> vars) {
        String msg = prefix() + color(apply(template, vars));
        sender.sendMessage(msg);
    }

    /**
     * 3) 通过消息键发送（占位符：可变参数）。
     * <p>会先使用翻译函数将 {@code key} 解析为模板，再进行占位符替换与颜色转换。</p>
     *
     * @param player  目标玩家
     * @param key    语言键，例如 {@code "message.no-item-in-hand"}
     * @param kv     以 {@code key, value, ...} 形式给定的占位符参数
     */
    public void sendKey(Player player, String key, Object... kv) {
        sendKey((CommandSender) player, key, kv);
    }

    /**
     * 3a) 通过消息键发送到任意 CommandSender（占位符：可变参数）。
     */
    public void sendKey(CommandSender sender, String key, Object... kv) {
        sendKey(sender, key, Vars.of(kv));
    }

    /**
     * 4) 通过消息键发送（占位符：Map）。
     * <p>会先使用翻译函数将 {@code key} 解析为模板，再进行占位符替换与颜色转换。</p>
     *
     * @param player     目标玩家
     * @param key       语言键，例如 {@code "message.no-item-in-hand"}
     * @param vars      占位符参数映射，{@code key -> value}
     */
    public void sendKey(Player player, String key, Map<String, ?> vars) {
        sendKey((CommandSender) player, key, vars);
    }

    /**
     * 4a) 通过消息键发送到任意 CommandSender（占位符：Map）。
     */
    public void sendKey(CommandSender sender, String key, Map<String, ?> vars) {
        String tmpl = translator.apply(key);
        String msg = prefix() + color(apply(tmpl, vars));
        sender.sendMessage(msg);
    }

    // ─────────────────────────────── 附：Title / ActionBar ───────────────────────────────

    /**
     * 以「模板文本」发送 Title（占位符：可变参数）。
     *
     * @param player   目标玩家
     * @param title    标题模板
     * @param subtitle 副标题模板
     * @param fadeIn   淡入 tick
     * @param stay     停留 tick
     * @param fadeOut  淡出 tick
     * @param kv       {@code key, value, ...} 形式的占位符参数
     */
    public void sendTitleText(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut, Object... kv) {
        sendTitleText(player, title, subtitle, fadeIn, stay, fadeOut, Vars.of(kv));
    }

    /**
     * 以「模板文本」发送 Title（占位符：Map）。
     *
     * @param player   目标玩家
     * @param title    标题模板
     * @param subtitle 副标题模板
     * @param fadeIn   淡入 tick
     * @param stay     停留 tick
     * @param fadeOut  淡出 tick
     * @param vars     占位符参数映射
     */
    public void sendTitleText(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut, Map<String, ?> vars) {
        sendTitleText((CommandSender) player, title, subtitle, fadeIn, stay, fadeOut, vars);
    }

    /**
     * 以「模板文本」发送 Title 到任意 CommandSender；若非玩家则回退为一条合并的聊天行。
     */
    public void sendTitleText(CommandSender sender, String title, String subtitle, int fadeIn, int stay, int fadeOut, Map<String, ?> vars) {
        if (sender instanceof Player p) {
            p.sendTitle(color(apply(title, vars)), color(apply(subtitle, vars)), fadeIn, stay, fadeOut);
        } else {
            sender.sendMessage(prefix() + color(apply(title + " | " + subtitle, vars)));
        }
    }

    /**
     * 以「消息键」发送 Title（占位符：可变参数）。
     *
     * @param player    目标玩家
     * @param titleKey 标题的语言键
     * @param subKey   副标题的语言键
     * @param fadeIn   淡入 tick
     * @param stay     停留 tick
     * @param fadeOut  淡出 tick
     * @param kv       {@code key, value, ...} 形式的占位符参数
     */
    public void sendTitleKey(Player player, String titleKey, String subKey, int fadeIn, int stay, int fadeOut, Object... kv) {
        sendTitleKey(player, titleKey, subKey, fadeIn, stay, fadeOut, Vars.of(kv));
    }

    /**
     * 以「消息键」发送 Title（占位符：Map）。
     *
     * @param player   目标玩家
     * @param titleKey 标题的语言键
     * @param subKey   副标题的语言键
     * @param fadeIn   淡入 tick
     * @param stay     停留 tick
     * @param fadeOut  淡出 tick
     * @param vars     占位符参数映射
     */
    public void sendTitleKey(Player player, String titleKey, String subKey, int fadeIn, int stay, int fadeOut, Map<String, ?> vars) {
        sendTitleKey((CommandSender) player, titleKey, subKey, fadeIn, stay, fadeOut, vars);
    }

    /**
     * 以「消息键」发送 Title 到任意 CommandSender；若非玩家则回退为一条合并的聊天行。
     */
    public void sendTitleKey(CommandSender sender, String titleKey, String subKey, int fadeIn, int stay, int fadeOut, Map<String, ?> vars) {
        String t = translator.apply(titleKey);
        String s = translator.apply(subKey);
        if (sender instanceof Player p) {
            p.sendTitle(color(apply(t, vars)), color(apply(s, vars)), fadeIn, stay, fadeOut);
        } else {
            sender.sendMessage(prefix() + color(apply(t + " | " + s, vars)));
        }
    }

    /**
     * 以「模板文本」发送 ActionBar（占位符：可变参数）。
     *
     * @param player     目标玩家
     * @param template  ActionBar 模板
     * @param kv        {@code key, value, ...} 形式的占位符参数
     */
    public void sendActionBarText(Player player, String template, Object... kv) {
        sendActionBarText(player, template, Vars.of(kv));
    }

    /**
     * 以「模板文本」发送 ActionBar（占位符：Map）。
     *
     * @param player       目标玩家
     * @param template ActionBar 模板
     * @param vars     占位符参数映射
     */
    public void sendActionBarText(Player player, String template, Map<String, ?> vars) {
        sendActionBarText((CommandSender) player, template, vars);
    }

    /**
     * 以「模板文本」发送 ActionBar 到任意 CommandSender；若非玩家则回退为聊天行。
     */
    public void sendActionBarText(CommandSender sender, String template, Map<String, ?> vars) {
        String msg = color(apply(template, vars));
        if (sender instanceof Player p) {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
        } else {
            sender.sendMessage(prefix() + msg);
        }
    }

    /**
     * 以「消息键」发送 ActionBar（占位符：可变参数）。
     *
     * @param player  目标玩家
     * @param key     语言键
     * @param kv      {@code key, value, ...} 形式的占位符参数
     */
    public void sendActionBarKey(Player player, String key, Object... kv) {
        sendActionBarKey(player, key, Vars.of(kv));
    }

    /**
     * 以「消息键」发送 ActionBar（占位符：Map）。
     *
     * @param player    目标玩家
     * @param key       语言键
     * @param vars      占位符参数映射
     */
    public void sendActionBarKey(Player player, String key, Map<String, ?> vars) {
        sendActionBarKey((CommandSender) player, key, vars);
    }

    /**
     * 以「消息键」发送 ActionBar 到任意 CommandSender；若非玩家则回退为聊天行。
     */
    public void sendActionBarKey(CommandSender sender, String key, Map<String, ?> vars) {
        String msg = color(apply(translator.apply(key), vars));
        if (sender instanceof Player p) {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
        } else {
            sender.sendMessage(prefix() + msg);
        }
    }

    // ───────────────────────────────── 实用工具 ─────────────────────────────────
    private String apply(String tmpl, Map<String, ?> vars) {
        return Placeholders.apply(tmpl, vars);
    }

    private String color(String s) {
        return ColorCodes.ampersandToSection(s, hexColor);
    }

    private static boolean isAtLeast116() {
        String v = VersionDetector.nmsSuffix(); // v1_20_R4 / v1_12_R1
        if (!v.startsWith("v1_")) return true;
        try {
            int minor = Integer.parseInt(v.substring(3, v.indexOf('_', 3)));
            return minor >= 16;
        } catch (Exception e) {
            return true;
        }
    }

    private String prefix() {
        try { return prefixSupplier.get(); } catch (Throwable ignored) { return ""; }
    }

    /**
     * 工具：把可变参数 {@code key, value, key, value...} 转为 Map。
     */
    public static final class Vars {
        /**
         * 将 {@code key, value, key, value...} 形式的实参转为 {@link Map}。
         *
         * @param kv 交替出现的键值序列，长度必须为偶数
         * @return 一个新的 {@link Map}，按插入顺序保存键值；若未传参则返回空 Map
         * @throws IllegalArgumentException 当参数个数为奇数时抛出
         */
        public static Map<String, Object> of(Object... kv) {
            if (kv == null || kv.length == 0) return java.util.Map.of();
            if ((kv.length & 1) == 1) throw new IllegalArgumentException("odd kv length");
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
            return m;
        }
    }
}