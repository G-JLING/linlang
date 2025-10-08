package core.linlang.command.message.i18n;

import core.linlang.command.message.CommandMessageKeys;
import api.linlang.file.annotations.LangPack;
import api.linlang.file.implement.LocaleProvider;
import api.linlang.file.annotations.NamingStyle;
import api.linlang.file.types.FileType;

@NamingStyle(NamingStyle.Style.KEBAB)
@LangPack(locale = "en_GB", format = FileType.YAML, path = "linlang/lincommand/lang")
public final class EnGB implements LocaleProvider<CommandMessageKeys> {

    @Override public String locale() { return "en_GB"; }

    @Override public void define(CommandMessageKeys k) {
        // prompt
        k.prompt.clickBlock   = "§fClick a block to continue";
        k.prompt.breakBlock   = "§fBreak a block to continue";
        k.prompt.placeBlock   = "§fPlace a block to continue";
        k.prompt.clickEntity  = "§fClick an entity to continue";
        k.prompt.damageEntity = "§fAttack an entity to continue";
        k.prompt.killEntity   = "§fKill an entity to continue";
        k.prompt.clickItem    = "§fClick an item in your inventory to continue";
        k.prompt.shootBlock   = "§fHit a block with a projectile to continue";

        // error
        k.error.badArg          = "§cError! §fUnknown argument or invalid parameter";
        k.error.noPerm          = "§cError! §fYou do not have permission to execute this command";
        k.error.unknownCommand  = "§cError! §fThe command you entered does not exist";
        k.error.execTarget      = "§cError! §fThis command cannot be executed from your current context";
        k.error.typeNoResolver  = "§fUnknown parameter resolver";
        k.error.enumNotFount    = "§fThe value entered is not a valid enum option";
        k.error.intRange        = "§fThe value entered is not an integer, or is out of range for int";
        k.error.doubleRange     = "§fThe value entered is not a floating-point number, or is out of range for double";
        k.error.stringRegex     = "§fThe value entered does not match the required pattern";

        // help
        k.help.header = "§fHelp";
        k.help.legend = "§7<>=required, []=optional";
        k.help.usage  = "§fUsage: ";
        k.help.homePage = "[At first page]";
        k.help.lastPage = "[At last page]";
        k.help.previousPage = "§c[<]";
        k.help.nextPage = "§c[>]";
        k.help.pageInfo = " §f- Page §c{current} §fof §c{total_pages} §f- ";
        k.help.hoverLeft = "View previous page";
        k.help.hoverRight = "View next page";
    }
}