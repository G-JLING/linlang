package adapter.linlang.bukkit.command.message;

import api.linlang.command.CommandMessages;

/** 使用 CommandLangKeys 的消息提供器实现。 */
public final class LangBackedMessages implements CommandMessages {
    private final CommandLangKeys keys;
    public LangBackedMessages(CommandLangKeys keys){ this.keys = keys; }

    @Override public String get(String key, Object... kv){
        return switch (key) {
            case "prompt.click.block"   -> keys.prompt.clickBlock;
            case "prompt.break.block"   -> keys.prompt.breakBlock;
            case "prompt.place.block"   -> keys.prompt.placeBlock;
            case "prompt.click.entity"  -> keys.prompt.clickEntity;
            case "prompt.damage.entity" -> keys.prompt.damageEntity;
            case "prompt.kill.entity"   -> keys.prompt.killEntity;
            case "prompt.click.item"    -> keys.prompt.clickItem;
            case "prompt.shoot.block"   -> keys.prompt.shootBlock;
            case "error.bad-arg"        -> keys.error.badArg;
            case "error.no-perm"        -> keys.error.noPerm;
            case "error.type.no-resolver" -> keys.error.typeNoResolver;
            case "help.header"          -> keys.help.header;
            case "help.legend"          -> keys.help.legend;
            case "help.usage"           -> keys.help.usage;
            default -> key;
        };
    }
}