package core.linlang.command.message;

import api.linlang.file.file.FileType;
import api.linlang.file.file.annotations.I18nComment;
import api.linlang.file.file.annotations.LangPack;
import api.linlang.file.file.annotations.NamingStyle;

@NamingStyle(NamingStyle.Style.KEBAB)
@LangPack(path = "linlang/lincommand/message", format = FileType.YAML, normalizeLocale = true)
public class CommandMessageKeys {
    public Prompt prompt = new Prompt();
    public Error error = new Error();
    public Help help = new Help();

    public static class Prompt {
        public String clickBlock;
        public String breakBlock;
        public String placeBlock;
        public String clickEntity;
        public String damageEntity;
        public String killEntity;
        public String clickItem;
        public String shootBlock;
    }

    public static class Error {
        public String badArg;
        public String noPerm;
        public String unknownCommand;
        public String execTarget;
        public String typeNoResolver;
        public String enumNotFount;
        public String intRange;
        public String doubleRange;
        public String stringRegex;

        public String exception;
    }

    public static class Help {
        public String header;
        public String legend;
        public String usage;
        public String homePage;
        public String lastPage;
        public String previousPage;
        public String nextPage;
        public String pageInfo;
        public String hoverLeft;
        public String hoverRight;

    }


}