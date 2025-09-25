package adapter.linlang.bukkit.common;

// impl.io.linlang.adapter.bukkit.common.file.Messenger

import adapter.linlang.bukkit.file.common.file.VersionDetector;
import api.linlang.file.service.LangService;
import core.linlang.file.text.ColorCodes;
import core.linlang.file.text.Placeholders;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

import java.util.Map;

/* 统一发送：lang key 或原文，{var} 占位，&→§ 上色。 */
public final class Messenger {
    private final LangService lang;
    private final boolean hexColor; // 1.16+ -> true
    public Messenger(LangService lang){
        this.lang = lang;
        this.hexColor = isAtLeast116();
    }

    /* 文本来源 -> key */
    public void sendMsg(Player p, String key, Map<String, ?> vars){
        sendRawMsg(p, resolve(key, vars));
    }
    public void title(Player p, String titleKey, String subKey, int in, int stay, int out, Map<String, ?> vars){
        String t = resolve(titleKey, vars);
        String s = resolve(subKey, vars);
        p.sendTitle(t, s, in, stay, out);
    }
    public void actionBar(Player p, String key, Map<String, ?> vars){
        String msg = resolve(key, vars);
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
    }

    /* 文本来源 -> 原文*/
    public void sendRawMsg(Player p, String raw, Map<String, ?> vars){
        p.sendMessage(color(apply(raw, vars)));
    }
    public void sendRawMsg(Player p, String raw){ p.sendMessage(color(raw)); }
    public void titleRaw(Player p, String title, String sub, int in, int stay, int out, Map<String, ?> vars){
        p.sendTitle(color(apply(title, vars)), color(apply(sub, vars)), in, stay, out);
    }
    public void actionBarRaw(Player p, String raw, Map<String, ?> vars){
        String msg = color(apply(raw, vars));
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(msg));
    }
    public void sendMsg(Player p, String template, Object... kv){
        sendRawMsg(p, template, Vars.of(kv));
    }
    public void actionBar(Player p, String template, Object... kv){
        actionBarRaw(p, template, Vars.of(kv));
    }
    public void titleRaw(Player p, String t, String s, int in, int stay, int out, Object... kv){
        titleRaw(p, t, s, in, stay, out, Vars.of(kv));
    }
    public void sendMsgKey(Player p, String key, Object... kv){
        sendMsg(p, lang.tr(key), kv);
    }

    /* 管线 */
    private String resolve(String key, Map<String, ?> vars){
        String tmpl = lang.tr(key);
        return color(apply(tmpl, vars));
    }
    private String apply(String tmpl, Map<String, ?> vars){ return Placeholders.apply(tmpl, vars); }
    private String color(String s){ return ColorCodes.ampersandToSection(s, hexColor); }

    private static boolean isAtLeast116(){
        String v = VersionDetector.nmsSuffix(); // v1_20_R4 / v1_12_R1
        if (!v.startsWith("v1_")) return true;
        try {
            int minor = Integer.parseInt(v.substring(3, v.indexOf('_', 3)));
            return minor >= 16;
        } catch (Exception e){ return true; }
    }

    public final class Vars {
        public static Map<String,Object> of(Object... kv){
            if (kv==null || kv.length==0) return java.util.Map.of();
            if ((kv.length & 1) == 1) throw new IllegalArgumentException("odd kv length");
            java.util.Map<String,Object> m = new java.util.LinkedHashMap<>();
            for (int i=0;i<kv.length;i+=2) m.put(String.valueOf(kv[i]), kv[i+1]);
            return m;
        }
    }
}