package core.linlang.file.util;

import api.linlang.audit.LinAudit;
import api.linlang.file.file.FileType;
import core.linlang.audit.log.BuiltinLog;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.json.JsonCodec;
import core.linlang.yaml.YamlCodec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生成缺失键差异文件，保留配置与语言服务各自的日志模板。
 */
public final class FileDiffWriter {
    private final LinAudit audit;
    private final BuiltinLog generated;
    private final BuiltinLog missing;

    public FileDiffWriter(LinAudit audit, BuiltinLog generated, BuiltinLog missing) {
        this.audit = audit;
        this.generated = generated;
        this.missing = missing;
    }

    public void write(Path file, FileType format, Map<String, Object> document, Set<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        try {
            String name = file.getFileName().toString();
            int extension = name.lastIndexOf('.');
            String base = extension > 0 ? name.substring(0, extension) : name;
            Path diff = file.getParent().resolve(base + "-diff" + (format == FileType.YAML ? ".yml" : ".json"));
            String output;
            if (format == FileType.YAML) {
                Map<String, List<String>> comments = new LinkedHashMap<>();
                for (String key : keys) comments.put(key, List.of("[Linlang] MISSING_KEY"));
                output = YamlCodec.dumpWithComments(document, comments);
            } else {
                Map<String, Object> wrapper = new LinkedHashMap<>();
                wrapper.put("_missing", new ArrayList<>(keys));
                wrapper.put("_file", document);
                output = JsonCodec.dump(wrapper);
            }
            IOs.writeString(diff, output);
            audit.logger().info(generated, "diff", diff);
            audit.logger().warn(missing, "file", file, "count", keys.size(), "diff", diff);
        } catch (Exception exception) {
            audit.problem().report(BuiltinProblemCatalog.DIFF_WRITE_FAILED, exception,
                    "file", file, "operation", generated == BuiltinLog.CONFIG_DIFF_GENERATED
                            ? "config-diff" : "language-diff");
        }
    }
}
