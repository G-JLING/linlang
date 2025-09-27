package api.linlang.command;

/**
 * 硬编码的默认命令提示消息。
 */
final class DefaultCommandMessages implements CommandMessages {
    static final DefaultCommandMessages INSTANCE = new DefaultCommandMessages();

    private DefaultCommandMessages() {
    }

    private static String prefix = "§f[§c琳琅命令§f] ";

    @Override
    public String get(String key, Object... kv) {
        return switch (key) {
            case "prompt.click.block"           -> prefix + "§f点击一个方块以继续";
            case "prompt.break.block"           -> prefix + "§f破坏一个方块以继续";
            case "prompt.place.block"           -> prefix + "§f放置一个方块以继续";
            case "prompt.click.entity"          -> prefix + "§f点击一个实体以继续";
            case "prompt.damage.entity"         -> prefix + "§f攻击一个实体以继续";
            case "prompt.kill.entity"           -> prefix + "§f击杀一个实体以继续";
            case "prompt.click.item"            -> prefix + "§f在背包中点击一个物品以继续";
            case "prompt.shoot.block"           -> prefix + "§f用弹射物或抛射物命中一个方块以继续";
            case "error.bad-arg"                -> prefix + "§c错误，§f未知的参数或参数错误";
            case "error.no-perm"                -> prefix + "§c错误，§f您没有执行此命令的权限";
            case "error.type.no-resolver"       -> prefix + "§f未知的参数解析器";
            case "error.enum.notfound"          -> prefix + "§f输入的内容不是有效的枚举参数";
            case "error.int.range"              -> prefix + "§f输入的内容不是整数类型，或超出了 int 类型允许的界限";
            case "error.double.range"           -> prefix + "§f输入的内容不是浮点数类型，或超出了 double 类型允许的界限";
            case "error.string.regex"           -> prefix + "§f输入的内容不是此参数规则允许的内容";
            case "help.header"                  -> "§f帮助";
            case "help.legend"                  -> "§7<>=强制参数, []=可选参数";
            case "help.usage"                   -> "§f用法: ";
            default -> key;
        };
    }
}