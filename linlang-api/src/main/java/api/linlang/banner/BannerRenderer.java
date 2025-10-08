package api.linlang.banner;

import java.util.*;
import java.util.function.Consumer;

public final class BannerRenderer {
    private BannerRenderer(){}

    /** 渲染 initials 的 ASCII 字，并打印固定信息块。行输出用 out.accept(line) */
    public static void print(AsciiFont font, BannerOptions opt, Consumer<String> out){
        // 顶部空行
        out.accept(" ");

        // 左侧 ASCII 牌头
        List<String> art = renderWord(font, opt.initials());

        // 右侧信息行（与 ASCII 行并排打印），中间空 4 个空格
        String devLine = String.join(", ", opt.developers());

        List<String> info = new ArrayList<>();
        info.add(opt.teamZh() + " " + opt.teamEn());
        info.add(opt.seriesZh() + " " + opt.seriesEn());
        info.add(opt.pluginZh() + " " + opt.pluginEn() + " " + opt.version());
        info.add("开发者: " + devLine + " | 由 琳琅(Linlang) 构建");
        info.add(opt.site());

        int rows = Math.max(art.size(), info.size());
        for (int i = 0; i < rows; i++) {
            String left  = i < art.size()  ? art.get(i)  : pad(font);
            String right = i < info.size() ? info.get(i) : "";
            out.accept(left + "    " + right);
        }

        // 底部空行
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