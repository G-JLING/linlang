package audit.linlang.audit;

import api.linlang.file.annotations.ConfigFile;

// linlang-called/src/main/java/io/linlang/called/AuditConfig.java
@ConfigFile(name="audit", path="linlang")
public class AuditConfig {
    public String level = "INFO";            // DEBUG/INFO/WARN/ERROR
    public boolean json = true;
    public Output console = new Output();
    public Output file = new Output("linlang/audit/linlang.log", true);
    public Output audit = new Output("linlang/audit/called.log", true);

    public static class Output {
        public String path = "";
        public boolean enabled = false;
        public String rotate = "size=10MB,backups=5"; // 简易策略
        public Output() {}
        public Output(String p, boolean e){ this.path=p; this.enabled=e; }
    }
}