package api.linlang.command;

/**
 * 硬编码的默认命令提示消息。
 */
final class DefaultCommandMessage implements CommandMessages {
    static final DefaultCommandMessage INSTANCE = new DefaultCommandMessage();

    private DefaultCommandMessage() {
    }

    @Override
    public String get(String key, Object... kv) {
        return switch (key) {
            case "prompt.click.block"           -> "§f点击一个方块以继续";
            case "prompt.break.block"           -> "§f破坏一个方块以继续";
            case "prompt.place.block"           -> "§f放置一个方块以继续";
            case "prompt.click.entity"          -> "§f点击一个实体以继续";
            case "prompt.damage.entity"         -> "§f攻击一个实体以继续";
            case "prompt.kill.entity"           -> "§f击杀一个实体以继续";
            case "prompt.click.item"            -> "§f在背包中点击一个物品以继续";
            case "prompt.shoot.block"           -> "§f用弹射物或抛射物命中一个方块以继续";
            case "error.bad-arg"                -> "§c错误! §f未知的参数或参数错误";
            case "error.exec-target"            -> "§c错误! §f此命令不可以在当前位置执行";
            case "error.no-perm"                -> "§c错误! §f您没有执行此命令的权限";
            case "error.unknown-command"        -> "§c错误! §f您输入了一个不存在的命令";
            case "error.exception"              -> "§c错误! 发生了一个内部错误，请联系 JLING(magicpowered@icloud.com)";
            case "error.type.no-resolver"       -> "§f未知的参数解析器";
            case "error.enum.notfound"          -> "§f输入的内容不是有效的枚举参数";
            case "error.int.range"              -> "§f输入的内容不是整数类型，或超出了 int 类型允许的界限";
            case "error.double.range"           -> "§f输入的内容不是浮点数类型，或超出了 double 类型允许的界限";
            case "error.string.regex"           -> "§f输入的内容不是此参数规则允许的内容";
            case "help.header"                  -> "§f帮助";
            case "help.legend"                  -> "§7<>=强制参数, []=可选参数";
            case "help.usage"                   -> "§f用法: ";
            case "help.home-page"               -> "已是首页";
            case "help.last-page"               -> "已是尾页";
            case "help.previous-page"           -> "§c[<]";
            case "help.next-page"               -> "§f[>]";
            case "help.page-info"               -> " §f-当前第 §c{current} §f页，共 §c{total_pages} §f页- ";
            case "help.hover-left"              -> "查看上一页";
            case "help.hover-right"             -> "查看下一页";
            default -> key;
        };
    }
}