package adapter.linlang.bukkit.command.message;

import api.linlang.file.annotations.LangPack;
import api.linlang.file.types.FileFormat;

/** 命令系统语言键。可由 LangService 绑定为语言文件。 */

@LangPack(locale = "zh_CN", format = FileFormat.YAML, path = "linlang", name = "command-message")
public class CommandLangKeys {
    public Prompt prompt = new Prompt();
    public Error error = new Error();
    public Help help = new Help();

    public static class Prompt {
        public String clickBlock  = "§f点击一个方块以继续";
        public String breakBlock  = "§f破坏一个方块以继续";
        public String placeBlock  = "§f放置一个方块以继续";
        public String clickEntity = "§f点击一个实体以继续";
        public String damageEntity= "§f攻击一个实体以继续";
        public String killEntity  = "§f击杀一个实体以继续";
        public String clickItem   = "§f在背包中点击一个物品以继续";
        public String shootBlock  = "§f用弹射物或抛射物命中一个方块以继续";
    }
    public static class Error {
        public String badArg = "§c错误，§f未知的参数或参数错误";
        public String noPerm = "§c错误，§f您没有执行此命令的权限";
        public String typeNoResolver = "§f未知的参数解析器";
        public String enumNotFount = "§f输入的内容不是有效的枚举参数";
        public String intRange = "§f输入的内容不是整数类型，或超出了 int 类型允许的界限";
        public String doubleRange = "§f输入的内容不是浮点数类型，或超出了 double 类型允许的界限";
        public String stringRegex = "§f输入的内容不是此参数规则允许的内容";
    }
    public static class Help {
        public String header = "§f帮助";
        public String legend = "§7<>=强制参数, []=可选参数";
        public String usage = "§f用法: ";
    }
}