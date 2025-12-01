package audit.linlang.audit;


import api.linlang.file.file.FileType;
import api.linlang.file.file.annotations.Comment;
import api.linlang.file.file.annotations.ConfigFile;
import api.linlang.file.file.annotations.NamingStyle;

@ConfigFile(name="audit", path="linlang/linlog", format= FileType.YAML)
@NamingStyle(NamingStyle.Style.KEBAB)
@Comment({"linlang 日志与审计 主配置文件", "linlang log and audit configauration file"})
public class AuditConfig {
    @Comment({"当前日志等级。从高到低，枚举 DEBUG、INFO、WARN、ERROR", "current log level. form high to low, enum: DEBUG, INFO, WARN, ERROR"})
    public String level = "INFO";
    @Comment({"以 JSON 格式输出吗？", "output in JSON format?"})
    public boolean json = true;
    @Comment({"输出到控制台", "output to console"})
    public Output console = new Output(true, false);

    @Comment({"输出日志到到文件", "output log to file"})
    public Output file = new Output("linlang/audit/log.log", false, true);

    @Comment({"输出审计到文件", "output audit to log"})
    public Output audit = new Output("linlang/audit/audit.log", false, true);

    public static class Output {

        @Comment({"输出位置", "output path"})
        public String path = "";

        @Comment({"是否启用此位置", "enable this output location?"})
        public boolean enabled = true;

        @Comment({"是否以 JSON 格式输出，此配置项优先级更高", "output in JSON format? this key has a higher priority"})
        public Boolean json = null;

        @Comment({"单个文件最大大小", "maximum size of a single file"})
        public int sizeMb = 10;

        @Comment({"同时保留的文件数量", "count of retained files"})
        public int retained = 5;

        public Output() {}
        public Output(boolean e, boolean r) {this.enabled = e;this.json = r;}
        public Output(String p, boolean e, boolean r){ this.path=p; this.enabled=e; this.json=r; }
    }
}