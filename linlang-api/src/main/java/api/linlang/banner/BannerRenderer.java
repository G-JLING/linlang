package api.linlang.banner;

import java.util.*;
import java.util.function.Consumer;

public final class BannerRenderer {
    private BannerRenderer(){}

    /** 渲染 initials 的 ASCII 字，并打印固定信息块。行输出用 out.accept(line) */
    public static void print(AsciiFont font, BannerOptions opt, Consumer<String> out){
        // 空行
        out.accept(" ");
        // ASCII 牌头
        List<String> lines = renderWord(font, opt.initials());
        for (String ln : lines) out.accept(ln + "    " + opt.teamZh()+" "+opt.teamEn());
        // 第二行起：系列、插件、作者
        out.accept(pad(font) + "    " + opt.seriesZh()+" "+opt.seriesEn());
        out.accept(pad(font) + "    " + opt.pluginZh()+" "+opt.pluginEn()+" "+opt.version());
        out.accept(pad(font) + "    开发者: "+opt.developer());
        out.accept(pad(font) + "    "+opt.site());
        out.accept(" ");
    }

    /** 仅渲染单词的 ASCII 行集合 */
    public static List<String> renderWord(AsciiFont font, String word){
        int h = font.height();
        List<String> out = new ArrayList<>(h);
        for (int r=0;r<h;r++){
            StringBuilder sb = new StringBuilder("  "); // 左侧缩进
            for (int i=0;i<word.length();i++){
                char ch = word.charAt(i);
                List<String> g = font.glyph(ch);
                String piece = r < g.size() ? g.get(r) : "";
                sb.append(piece);
                if (i < word.length()-1) sb.append(" ".repeat(font.gap()));
            }
            out.add(sb.toString());
        }
        return out;
    }

    private static String pad(AsciiFont font){
        // 与牌头左缩进对齐
        return "  ";
    }
}