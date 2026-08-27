package core.linlang.command.model;

// lincommand-core model
// linlang-core/src/main/java/io/linlang/lincommand/core/Model.java

import api.linlang.command.LinCommand;
import api.linlang.command.group.CommandFailureHandler;
import api.linlang.command.group.CommandSuccessHandler;

import java.util.*;

public final class Model {
    public static final class Node {
        public List<String> literals = new ArrayList<>(); // e.g. ["bukkit","reload"]
        public List<Param>  params   = new ArrayList<>();
        public Exec exec;
        public String usage;
        public Map<String,String> descI18n;
        public List<CommandSuccessHandler> successHandlers = new ArrayList<>();
        public List<CommandFailureHandler> failureHandlers = new ArrayList<>();
    }
    public static final class Param {
        public String name; public boolean optional; public String defVal; public String desc;
        // 复合：允许多种类型 union，如 enum|minecraft:item
        public List<TypeSpec> types = new ArrayList<>();
        public boolean i18nTag;
    }
    public static final class TypeSpec {
        public String id;                        // enum / int / double / string / regex / minecraft:item / event:block
        public Map<String,String> meta = new LinkedHashMap<>(); // enum 值/范围/regex 等
    }
    public static final class Exec {
        public LinCommand.CommandExecutor fn;
        public List<String> permissions = new ArrayList<>();
        public LinCommand.ExecTarget target;
    }
}
