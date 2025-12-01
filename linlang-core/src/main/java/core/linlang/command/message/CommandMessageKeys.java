package core.linlang.command.message;


import api.linlang.file.file.annotations.I18nComment;
import api.linlang.file.file.annotations.NamingStyle;

@NamingStyle(NamingStyle.Style.KEBAB)
@I18nComment(locale = "zh_CN", lines = "琳琅命令的消息文件。")
@I18nComment(locale = "en_GB", lines = "LinCommand messages file")
public class CommandMessageKeys {
    @I18nComment(locale = "zh_CN", lines = "交互提示")
    @I18nComment(locale = "en_GB", lines = "Interaction prompts")
    public Prompt prompt = new Prompt();

    @I18nComment(locale = "zh_CN", lines = "错误提示")
    @I18nComment(locale = "en_GB", lines = "Error messages")
    public Error error = new Error();

    @I18nComment(locale = "zh_CN", lines = "帮助文本")
    @I18nComment(locale = "en_GB", lines = "Help texts")
    public Help help = new Help();

    public static class Prompt {
        @I18nComment(locale = "zh_CN", lines = "点击一个方块以继续")
        @I18nComment(locale = "en_GB", lines = "Click a block to continue")
        public String clickBlock;

        @I18nComment(locale = "zh_CN", lines = "破坏一个方块以继续")
        @I18nComment(locale = "en_GB", lines = "Break a block to continue")
        public String breakBlock;

        @I18nComment(locale = "zh_CN", lines = "放置一个方块以继续")
        @I18nComment(locale = "en_GB", lines = "Place a block to continue")
        public String placeBlock;

        @I18nComment(locale = "zh_CN", lines = "点击一个实体以继续")
        @I18nComment(locale = "en_GB", lines = "Click an entity to continue")
        public String clickEntity;

        @I18nComment(locale = "zh_CN", lines = "攻击一个实体以继续")
        @I18nComment(locale = "en_GB", lines = "Damage an entity to continue")
        public String damageEntity;

        @I18nComment(locale = "zh_CN", lines = "击杀一个实体以继续")
        @I18nComment(locale = "en_GB", lines = "Kill an entity to continue")
        public String killEntity;

        @I18nComment(locale = "zh_CN", lines = "在背包中点击一个物品以继续")
        @I18nComment(locale = "en_GB", lines = "Click an item in inventory to continue")
        public String clickItem;

        @I18nComment(locale = "zh_CN", lines = "用弹射物或抛射物命中一个方块以继续")
        @I18nComment(locale = "en_GB", lines = "Hit a block with a projectile to continue")
        public String shootBlock;
    }
    public static class Error {
        @I18nComment(locale = "zh_CN", lines = "错误! 未知的参数或参数错误")
        @I18nComment(locale = "en_GB", lines = "Error! Unknown or invalid argument")
        public String badArg;

        @I18nComment(locale = "zh_CN", lines = "错误! 您没有执行此命令的权限")
        @I18nComment(locale = "en_GB", lines = "Error! You do not have permission to run this command")
        public String noPerm;

        @I18nComment(locale = "zh_CN", lines = "错误! 您输入了一个不存在的命令")
        @I18nComment(locale = "en_GB", lines = "Error! Unknown command")
        public String unknownCommand;

        @I18nComment(locale = "zh_CN", lines = "错误! 此命令不可以在当前位置执行")
        @I18nComment(locale = "en_GB", lines = "Error! This command cannot be executed from here")
        public String execTarget;

        @I18nComment(locale = "zh_CN", lines = "未知的参数解析器")
        @I18nComment(locale = "en_GB", lines = "Unknown argument resolver")
        public String typeNoResolver;

        @I18nComment(locale = "zh_CN", lines = "输入的内容不是有效的枚举参数")
        @I18nComment(locale = "en_GB", lines = "The input is not a valid enum value")
        public String enumNotFount;

        @I18nComment(locale = "zh_CN", lines = "输入的内容不是整数类型，或超出了 int 类型允许的界限")
        @I18nComment(locale = "en_GB", lines = "Input is not an integer or out of range")
        public String intRange;

        @I18nComment(locale = "zh_CN", lines = "输入的内容不是浮点数类型，或超出了 double 类型允许的界限")
        @I18nComment(locale = "en_GB", lines = "Input is not a floating point number or out of range")
        public String doubleRange;

        @I18nComment(locale = "zh_CN", lines = "输入的内容不是此参数规则允许的内容")
        @I18nComment(locale = "en_GB", lines = "Input does not match the allowed pattern")
        public String stringRegex;

        @I18nComment(locale = "zh_CN", lines = "发生了内部错误，请联系 JLING(magicpowered@icloud.com)")
        @I18nComment(locale = "en_GB", lines = "An internal error has occurred. Please contact JLING(magicpowered@icloud.com)")
        public String exception;
    }
    public static class Help {
        @I18nComment(locale = "zh_CN", lines = "帮助")
        @I18nComment(locale = "en_GB", lines = "Help")
        public String header;

        @I18nComment(locale = "zh_CN", lines = "<>=强制参数, []=可选参数")
        @I18nComment(locale = "en_GB", lines = "<>=required, []=optional")
        public String legend;

        @I18nComment(locale = "zh_CN", lines = "用法: ")
        @I18nComment(locale = "en_GB", lines = "Usage: ")
        public String usage;

        @I18nComment(locale = "zh_CN", lines = "已是首页")
        @I18nComment(locale = "en_GB", lines = "At first page")
        public String homePage;

        @I18nComment(locale = "zh_CN", lines = "已是尾页")
        @I18nComment(locale = "en_GB", lines = "At last page")
        public String lastPage;

        @I18nComment(locale = "zh_CN", lines = "上一页")
        @I18nComment(locale = "en_GB", lines = "previous page")
        public String previousPage;

        @I18nComment(locale = "zh_CN", lines = "下一页")
        @I18nComment(locale = "en_GB", lines = "next page")
        public String nextPage;

        @I18nComment(locale = "zh_CN", lines = "页码信息")
        @I18nComment(locale = "zh_CN", lines = "page number information")
        public String pageInfo;

        @I18nComment(locale = "zh_CN", lines = "悬浮时的提示")
        @I18nComment(locale = "en_GB", lines = "prompt when the cursor is hovering")
        public String hoverLeft;
        @I18nComment(locale = "zh_CN", lines = "悬浮时的提示")
        @I18nComment(locale = "en_GB", lines = "prompt when the cursor is hovering")
        public String hoverRight;

    }


}