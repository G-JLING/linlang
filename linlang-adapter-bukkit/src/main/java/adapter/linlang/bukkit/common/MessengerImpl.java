package adapter.linlang.bukkit.common;

/*
 * MessengerImpl：统一向玩家发送消息。
 * 约定：占位符使用 {key}，支持可变参数或 Map。
 * 颜色：将 & 转为 §；1.16+ 支持 Hex。
 */

import adapter.linlang.bukkit.file.common.file.VersionDetector;
import api.linlang.file.service.LangService;
import api.linlang.message.LinMessenger;
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
 *
 * 功能：
 * <ul>
 *   <li>传入消息字符串或字符串形式的键</li>
 *   <li>占位符语法：{@code {placeholder}}，可变参数或 Map 传参；</li>
 *   <li>颜色转换：将 {@code &} 转为 {@code §}，在 1.16+ 支持 HEX；</li>
 *   <li>提供 Chat、Title、ActionBar 三种输出通道。</li>
 * </ul>
 */
public final class MessengerImpl implements LinMessenger {

    private final Function<String, String> translator; // key -> template
    private final boolean hexColor; // 1.16+ -> true

    // Optional prefix (e.g., plugin prefix). Defaults to empty.
    private Supplier<String> prefixSupplier = () -> "";

    /* ─────────────────────────────── 构造 ─────────────────────────────── */

    /**
     * 使用 {@link LangService} 作为翻译源的构造器。
     * 等价于传入 {@code translator = lang::tr}。
     */
    public MessengerImpl(LangService lang) {
        this(lang::tr);
    }

    /**
     * 使用自定义翻译函数的构造器。
     *
     * @param translator 翻译函数，输入消息键输出模板文本；若为 {@code null} 则回显键
     */
    public MessengerImpl(Function<String, String> translator) {
        this.translator = translator != null ? translator : (k -> k);
        this.hexColor = isAtLeast116();
    }

    /* ─────────────────────────────── 配置 ─────────────────────────────── */

    /** 设置固定前缀（仅聊天/控制台；Title/ActionBar 不附带前缀）。 */
    public MessengerImpl withPrefix(String prefix) {
        this.prefixSupplier = () -> (prefix == null ? "" : prefix);
        return this;
    }

    /** 设置动态前缀（运行时计算；仅聊天/控制台）。 */
    public MessengerImpl withPrefixProvider(Supplier<String> supplier) {
        this.prefixSupplier = (supplier == null ? () -> "" : supplier);
        return this;
    }

    /* ─────────────────────────────── 文本：模板 ─────────────────────────────── */

    /** 1) 文本到 Player（kv 变参）。 */
    public void sendText(Player player, String template, Object... kv) {
        sendText((CommandSender) player, template, kv);
    }

    /** 1a) 文本到任意对象（kv 变参，桥接）。 */
    public void sendText(Object recipient, String template, Object... kv) {
        sendText(recipient, template, Vars.of(kv));
    }

    /** 2) 文本到 Player（Map）。 */
    public void sendText(Player player, String template, Map<String, ?> vars) {
        sendText((CommandSender) player, template, vars);
    }

    /** 2a) 文本到任意对象（Map）。 */
    public void sendText(Object recipient, String template, Map<String, ?> vars) {
        String msg = prefix() + color(apply(template, vars));
        chat(recipient, msg);
    }

    /** 1b) 文本到 CommandSender（kv 变参）。 */
    public void sendText(CommandSender sender, String template, Object... kv) {
        sendText(sender, template, Vars.of(kv));
    }

    /** 2b) 文本到 CommandSender（Map）。 */
    public void sendText(CommandSender sender, String template, Map<String, ?> vars) {
        String msg = prefix() + color(apply(template, vars));
        sender.sendMessage(msg);
    }

    /* ─────────────────────────────── 文本：语言键 ─────────────────────────────── */

    /** 3) 语言键到 Player（kv 变参）。 */
    public void sendKey(Player player, String key, Object... kv) {
        sendKey((CommandSender) player, key, kv);
    }

    /** 3a) 语言键到任意对象（kv 变参，桥接）。 */
    public void sendKey(Object recipient, String key, Object... kv) {
        sendKey(recipient, key, Vars.of(kv));
    }

    /** 4) 语言键到 Player（Map）。 */
    public void sendKey(Player player, String key, Map<String, ?> vars) {
        sendKey((CommandSender) player, key, vars);
    }

    /** 4a) 语言键到任意对象（Map）。 */
    public void sendKey(Object recipient, String key, Map<String, ?> vars) {
        String tmpl = translator.apply(key);
        String msg = prefix() + color(apply(tmpl, vars));
        chat(recipient, msg);
    }


    /** 3b) 语言键到 CommandSender（kv 变参）。 */
    public void sendKey(CommandSender sender, String key, Object... kv) {
        sendKey(sender, key, Vars.of(kv));
    }

    /** 4b) 语言键到 CommandSender（Map）。 */
    public void sendKey(CommandSender sender, String key, Map<String, ?> vars) {
        String tmpl = translator.apply(key);
        String msg = prefix() + color(apply(tmpl, vars));
        sender.sendMessage(msg);
    }

    /* ─────────────────────────────── Title ─────────────────────────────── */

    /** Title（模板，kv 变参）到 Player。 */
    public void sendTitleText(Player player, String title, String subtitle,
                              int fadeIn, int stay, int fadeOut, Object... kv) {
        sendTitleText(player, title, subtitle, fadeIn, stay, fadeOut, Vars.of(kv));
    }

    /** Title（模板，Map）到 Player。 */
    public void sendTitleText(Player player, String title, String subtitle,
                              int fadeIn, int stay, int fadeOut, Map<String, ?> vars) {
        player.sendTitle(color(apply(title, vars)),
                color(apply(subtitle, vars)),
                fadeIn, stay, fadeOut);
    }

    /** Title（模板，Map）到任意对象；非玩家退化为聊天行。 */
    public void sendTitleText(Object recipient, String title, String subtitle,
                              int fadeIn, int stay, int fadeOut, Map<String, ?> vars) {
        if (recipient instanceof Player p) {
            sendTitleText(p, title, subtitle, fadeIn, stay, fadeOut, vars);
        } else {
            chat(recipient, prefix() + color(apply(title + " | " + subtitle, vars)));
        }
    }



    /** Title 文本到 CommandSender：回退为一条聊天行。 */
    public void sendTitleText(CommandSender sender, String title, String subtitle,
                              int fadeIn, int stay, int fadeOut, Map<String, ?> vars) {
        sender.sendMessage(prefix() + color(apply(title + " | " + subtitle, vars)));
    }

    /** Title（键，kv 变参）到 Player。 */
    public void sendTitleKey(Player player, String titleKey, String subKey,
                             int fadeIn, int stay, int fadeOut, Object... kv) {
        sendTitleKey(player, titleKey, subKey, fadeIn, stay, fadeOut, Vars.of(kv));
    }

    /** Title（键，Map）到 Player。 */
    public void sendTitleKey(Player player, String titleKey, String subKey,
                             int fadeIn, int stay, int fadeOut, Map<String, ?> vars) {
        String t = translator.apply(titleKey);
        String s = translator.apply(subKey);
        player.sendTitle(color(apply(t, vars)),
                color(apply(s, vars)),
                fadeIn, stay, fadeOut);
    }

    /** Title（键，Map）到任意对象；非玩家退化为聊天行。 */
    public void sendTitleKey(Object recipient, String titleKey, String subKey,
                             int fadeIn, int stay, int fadeOut, Map<String, ?> vars) {
        String t = translator.apply(titleKey);
        String s = translator.apply(subKey);
        if (recipient instanceof Player p) {
            p.sendTitle(color(apply(t, vars)), color(apply(s, vars)), fadeIn, stay, fadeOut);
        } else {
            chat(recipient, prefix() + color(apply(t + " | " + s, vars)));
        }
    }

    public void sendTitleKey(Object recipient, String titleKey, String subKey,
                             int fadeIn, int stay, int fadeOut, Object... kv) {
        sendTitleKey(recipient, titleKey, subKey, fadeIn, stay, fadeOut, Vars.of(kv));
    }

    public void sendTitleText(Object recipient, String title, String subtitle,
                              int fadeIn, int stay, int fadeOut, Object... kv) {
        sendTitleText(recipient, title, subtitle, fadeIn, stay, fadeOut, Vars.of(kv));
    }


    /** Title 键到 CommandSender：回退为一条聊天行。 */
    public void sendTitleKey(CommandSender sender, String titleKey, String subKey,
                             int fadeIn, int stay, int fadeOut, Map<String, ?> vars) {
        String t = translator.apply(titleKey);
        String s = translator.apply(subKey);
        sender.sendMessage(prefix() + color(apply(t + " | " + s, vars)));
    }

    /* ─────────────────────────────── ActionBar ─────────────────────────────── */

    /** ActionBar（模板，kv 变参）到 Player。 */
    public void sendActionBarText(Player player, String template, Object... kv) {
        sendActionBarText(player, template, Vars.of(kv));
    }

    /** ActionBar（模板，Map）到 Player。 */
    public void sendActionBarText(Player player, String template, Map<String, ?> vars) {
        String msg = color(apply(template, vars));
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
    }

    /** ActionBar（模板，Map）到任意对象；非玩家退化为聊天行。 */
    public void sendActionBarText(Object recipient, String template, Map<String, ?> vars) {
        String msg = color(apply(template, vars));
        if (recipient instanceof Player p) {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
        } else {
            chat(recipient, prefix() + msg);
        }
    }

    public void sendActionBarText(Object recipient, String template, Object... kv) {
        sendActionBarText(recipient, template, Vars.of(kv));
    }

    public void sendActionBarKey(Object recipient, String key, Object... kv) {
        sendActionBarKey(recipient, key, Vars.of(kv));
    }

    /** ActionBar（键，kv 变参）到 Player。 */
    public void sendActionBarKey(Player player, String key, Object... kv) {
        sendActionBarKey(player, key, Vars.of(kv));
    }

    /** ActionBar（键，Map）到 Player。 */
    public void sendActionBarKey(Player player, String key, Map<String, ?> vars) {
        String msg = color(apply(translator.apply(key), vars));
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
    }

    /** ActionBar（键，Map）到任意对象；非玩家退化为聊天行。 */
    public void sendActionBarKey(Object recipient, String key, Map<String, ?> vars) {
        String msg = color(apply(translator.apply(key), vars));
        if (recipient instanceof Player p) {
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
        } else {
            chat(recipient, prefix() + msg);
        }
    }

    /* ─────────────────────────────── 工具 ─────────────────────────────── */

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

    /** 将 {@code recipient} 解析为 {@link CommandSender}；若不是则返回 null。 */
    private static CommandSender asSender(Object recipient) {
        return (recipient instanceof CommandSender) ? (CommandSender) recipient : null;
    }

    /** 统一聊天输出（用于非玩家对象退化）；不支持的对象直接抛出异常便于定位问题。 */
    private void chat(Object recipient, String message) {
        CommandSender cs = asSender(recipient);
        if (cs != null) {
            cs.sendMessage(message);
        } else {
            throw new IllegalArgumentException("Unsupported recipient type: " +
                    (recipient == null ? "null" : recipient.getClass().getName()));
        }
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