package core.linlang.command.message;


import api.linlang.command.message.CommandMessages;

/**
 * 使用 CommandMessageKeys 的消息提供器实现。
 */
public final class CommandMessageRouter implements CommandMessages {
    private final CommandMessageKeys keys;
    private final CommandMessages defaults = CommandMessages.defaults();

    public CommandMessageRouter(CommandMessageKeys keys) {
        this.keys = keys;
    }

    @Override
    public String get(String key, Object... kv) {
        String value = switch (key) {
            case "prompt.click.block"       -> keys.prompt.clickBlock;
            case "prompt.break.block"       -> keys.prompt.breakBlock;
            case "prompt.place.block"       -> keys.prompt.placeBlock;
            case "prompt.click.entity"      -> keys.prompt.clickEntity;
            case "prompt.damage.entity"     -> keys.prompt.damageEntity;
            case "prompt.kill.entity"       -> keys.prompt.killEntity;
            case "prompt.click.item"        -> keys.prompt.clickItem;
            case "prompt.shoot.block"       -> keys.prompt.shootBlock;
            case "error.bad-arg"            -> keys.error.badArg;
            case "error.no-perm"            -> keys.error.noPerm;
            case "error.type.no-resolver"   -> keys.error.typeNoResolver;
            case "error.enum.notfound"      -> keys.error.enumNotFount;
            case "error.int.range"          -> keys.error.intRange;
            case "error.double.range"       -> keys.error.doubleRange;
            case "error.string.regex"       -> keys.error.stringRegex;
            case "error.exec-target"        -> keys.error.execTarget;
            case "error.unknown-command"    -> keys.error.unknownCommand;
            case "error.exception"          -> keys.error.exception;
            case "help.header"              -> keys.help.header;
            case "help.legend"              -> keys.help.legend;
            case "help.usage"               -> keys.help.usage;
            case "help.home-page"           -> keys.help.homePage;
            case "help.last-page"           -> keys.help.lastPage;
            case "help.previous-page"       -> keys.help.previousPage;
            case "help.next-page"           -> keys.help.nextPage;
            case "help.page-info"           -> keys.help.pageInfo;
            case "help.hover-left"          -> keys.help.hoverLeft;
            case "help.hover-right"         -> keys.help.hoverRight;
            case "info.header"              -> keys.info.header;
            case "info.plugin"              -> keys.info.plugin;
            case "info.authors"             -> keys.info.authors;
            case "info.build-version"       -> keys.info.buildVersion;
            case "info.linlang-version"     -> keys.info.linlangVersion;
            case "info.visit"               -> keys.info.visit;
            default -> null;
        };
        if (value == null) return defaults.get(key, kv);
        if (kv == null) return value;
        for (int index = 0; index + 1 < kv.length; index += 2) {
            value = value.replace("{" + kv[index] + "}", String.valueOf(kv[index + 1]));
        }
        return value;
    }
}
