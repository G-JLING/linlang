package core.linlang.audit.config;

import api.linlang.file.file.FileType;
import api.linlang.file.file.annotations.Comment;
import api.linlang.file.file.annotations.ConfigFile;
import api.linlang.file.file.annotations.NamingStyle;

/**
 * 琳琅日志与审计配置。
 * <p>建议：对每个插件单独绑定一份 AuditConfig，这样每个插件有自己的日志级别和输出路径。</p>
 */
@ConfigFile(name = "audit", path = "linlang/linlog", format = FileType.YAML)
@NamingStyle(NamingStyle.Style.KEBAB)
@Comment({"linlang 日志与审计 主配置文件", "linlang log and audit configuration file"})
public class AuditConfig {

    @Comment({"当前普通日志等级。从低到高：DEBUG < INFO < WARN < ERROR",
            "current log level. from low to high: DEBUG < INFO < WARN < ERROR"})
    public String level = "INFO";

    @Comment({"默认是否以 JSON 格式输出（可被各输出单独覆盖）", "default: output in JSON format or not"})
    public boolean json = true;

    @Comment({"输出到控制台", "output to console"})
    public Output console = new Output(true, false);

    @Comment({"输出普通日志到文件", "output log to file"})
    public Output file = new Output("linlang/audit/log.log", false, true);

    @Comment({"输出审计日志到文件", "output audit to file"})
    public Output audit = new Output("linlang/audit/audit.log", false, true);

    @Comment({"输出结构化问题到文件", "output structured problems to file"})
    public Output problem = new Output("linlang/audit/problem.log", false, true);

    @Comment({"文件写入队列容量", "file writer queue capacity"})
    public int queueCapacity = 4096;

    public static class Output {

        @Comment({"输出位置（由 PathResolver/ConfigService 解析）", "output path"})
        public String path = "";

        @Comment({"是否启用此输出", "enable this output location?"})
        public boolean enabled = true;

        @Comment({"是否以 JSON 格式输出，此配置项优先级更高",
                "output in JSON format? this key has a higher priority"})
        public Boolean json = null;

        @Comment({"单个文件最大大小（MB）", "maximum size of a single file (MB)"})
        public int sizeMb = 10;

        @Comment({"同时保留的文件数量（轮转个数）", "count of retained files"})
        public int retained = 5;

        public Output() {}

        public Output(boolean enabled, boolean json) {
            this.enabled = enabled;
            this.json = json;
        }

        public Output(String path, boolean enabled, boolean json) {
            this.path = path;
            this.enabled = enabled;
            this.json = json;
        }
    }
}
