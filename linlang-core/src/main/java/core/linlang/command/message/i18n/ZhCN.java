package core.linlang.command.message.i18n;

import core.linlang.command.message.CommandMessageKeys;
import api.linlang.file.annotations.LangPack;
import api.linlang.file.implement.LocaleProvider;
import api.linlang.file.annotations.NamingStyle;
import api.linlang.file.types.FileType;

@NamingStyle(NamingStyle.Style.KEBAB)
@LangPack(locale = "zh_CN", format = FileType.YAML, path = "linlang/lincommand/message")
public final class ZhCN implements LocaleProvider<CommandMessageKeys> {

    @Override public String locale() { return "zh_CN"; }

    @Override public void define(CommandMessageKeys k) {
        // prompt
        k.prompt.clickBlock   = "§f点击一个方块以继续";
        k.prompt.breakBlock   = "§f破坏一个方块以继续";
        k.prompt.placeBlock   = "§f放置一个方块以继续";
        k.prompt.clickEntity  = "§f点击一个实体以继续";
        k.prompt.damageEntity = "§f攻击一个实体以继续";
        k.prompt.killEntity   = "§f击杀一个实体以继续";
        k.prompt.clickItem    = "§f在背包中点击一个物品以继续";
        k.prompt.shootBlock   = "§f用弹射物或抛射物命中一个方块以继续";

        // error
        k.error.badArg          = "§c错误! §f未知的参数或参数错误";
        k.error.noPerm          = "§c错误! §f您没有执行此命令的权限";
        k.error.unknownCommand  = "§c错误! §f您输入了一个不存在的命令";
        k.error.execTarget      = "§c错误! §f此命令不可以在当前位置执行";
        k.error.exception       = "§c错误! 发生了一个内部错误，请联系 JLING(magicpowered@icloud.com)";
        k.error.typeNoResolver  = "§f未知的参数解析器";
        k.error.enumNotFount    = "§f输入的内容不是有效的枚举参数";
        k.error.intRange        = "§f输入的内容不是整数类型，或超出了 int 类型允许的界限";
        k.error.doubleRange     = "§f输入的内容不是浮点数类型，或超出了 double 类型允许的界限";
        k.error.stringRegex     = "§f输入的内容不是此参数规则允许的内容";

        // help
        k.help.header = "§f帮助";
        k.help.legend = "§7<>=强制参数, []=可选参数";
        k.help.usage  = "§f用法: ";
        k.help.homePage = "[已是首页]";
        k.help.lastPage = "[已是尾页]";
        k.help.previousPage = "§c[<]";
        k.help.nextPage = "§c[>]";
        k.help.pageInfo = " §f- 当前第 §c{current} §f页，共 §c{total_pages} §f页- ";
        k.help.hoverLeft = "查看上一页";
        k.help.hoverRight = "查看下一页";
    }
}