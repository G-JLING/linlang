package core.linlang.file.config;

import api.linlang.file.file.FileType;
import api.linlang.file.file.config.ConfigIssue;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.*;
import org.yaml.snakeyaml.tokens.CommentToken;
import org.yaml.snakeyaml.tokens.Token;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.scanner.ScannerImpl;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.*;
import java.util.*;

/**
 * 保留配置原文，在真实 YAML 节点前标注错误；无法注释时写入独立诊断文件。
 */
public final class ConfigDiagnostics {
    private static final String MARKER = "[Linlang:config] ";
    private static final String HEADER = "Linlang configuration diagnostics\n";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private ConfigDiagnostics() {}

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        return new Yaml(new SafeConstructor(options));
    }

    public static Map<String, Object> load(String raw, FileType format) {
        try {
            Object value = format == FileType.YAML ? yaml().load(clear(raw))
                    : raw.isBlank() ? null : JSON.readValue(raw, Object.class);
            if (value == null) return new LinkedHashMap<>();
            if (!(value instanceof Map<?, ?> map)) {
                throw new ConfigMappingException(List.of(new ConfigIssue("$", "文件根节点必须为键值对象")));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            checkTree(value, Collections.newSetFromMap(new IdentityHashMap<>()));
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new ConfigMappingException(List.of(new ConfigIssue("$", "根节点的键必须为字符串")));
                }
                result.put(key, entry.getValue());
            }
            return result;
        } catch (ConfigMappingException exception) {
            throw exception;
        } catch (Exception exception) {
            String position = "";
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof MarkedYAMLException marked && marked.getProblemMark() != null) {
                    position = "（原文第 " + (marked.getProblemMark().getLine() + 1) + " 行，第 "
                            + (marked.getProblemMark().getColumn() + 1) + " 列）";
                    break;
                }
                if (cause instanceof JsonProcessingException json && json.getLocation() != null) {
                    position = "（第 " + json.getLocation().getLineNr() + " 行，第 "
                            + json.getLocation().getColumnNr() + " 列）";
                    break;
                }
            }
            throw new ConfigMappingException(List.of(new ConfigIssue("$", format + " 语法错误" + position
                    + "，请检查缩进、括号、引号及重复键")));
        }
    }

    public static Path sidecar(Path file) {
        return file.resolveSibling(file.getFileName() + ".errors.txt");
    }

    private static void checkTree(Object value, Set<Object> stack) {
        if (!(value instanceof Map<?, ?>) && !(value instanceof Collection<?>)) return;
        if (!stack.add(value)) throw new ConfigMappingException(List.of(new ConfigIssue("$", "配置不支持循环别名")));
        if (value instanceof Map<?, ?> map) map.values().forEach(item -> checkTree(item, stack));
        else ((Collection<?>) value).forEach(item -> checkTree(item, stack));
        stack.remove(value);
    }

    public static String annotate(String raw, List<ConfigIssue> issues) {
        String clean = clear(raw);
        Map<String, Integer> positions = new LinkedHashMap<>();
        try {
            Node root = yaml().compose(new StringReader(clean));
            positions(root, "", positions, Collections.newSetFromMap(new IdentityHashMap<>()));
        } catch (RuntimeException ignored) {
            // 语法损坏时仅在文件头标注，不猜测键位置。
        }
        String separator = clean.contains("\r\n") ? "\r\n" : "\n";
        List<String> lines = new ArrayList<>(Arrays.asList(clean.split("\\r?\\n", -1)));
        Map<Integer, List<String>> comments = new TreeMap<>(Comparator.reverseOrder());
        for (ConfigIssue issue : issues) {
            int line = positions.getOrDefault(issue.key(), 0);
            if (line >= lines.size()) line = 0;
            String content = lines.get(line);
            int indent = 0;
            while (indent < content.length() && content.charAt(indent) == ' ') indent++;
            String comment = " ".repeat(indent) + "# " + MARKER + safe(issue.key()) + "：" + safe(issue.message());
            comments.computeIfAbsent(line, ignored -> new ArrayList<>()).add(comment);
        }
        comments.forEach(lines::addAll);
        return String.join(separator, lines);
    }

    private static void positions(Node node, String path, Map<String, Integer> result, Set<Node> stack) {
        if (node == null || !stack.add(node)) return;
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)) continue;
                String child = path.isEmpty() ? key.getValue() : path + "." + key.getValue();
                result.put(child, key.getStartMark().getLine());
                positions(tuple.getValueNode(), child, result, stack);
            }
        } else if (node instanceof SequenceNode sequence) {
            int index = 0;
            for (Node item : sequence.getValue()) {
                String child = path + "[" + index++ + "]";
                result.put(child, item.getStartMark().getLine());
                positions(item, child, result, stack);
            }
        }
        stack.remove(node);
    }

    public static String clear(String raw) {
        Set<Integer> owned = new HashSet<>();
        try {
            LoaderOptions options = new LoaderOptions();
            options.setProcessComments(true);
            ScannerImpl scanner = new ScannerImpl(new StreamReader(raw), options);
            while (scanner.checkToken()) {
                Token token = scanner.getToken();
                if (token instanceof CommentToken comment && comment.getValue().stripLeading().startsWith(MARKER)) {
                    owned.add(token.getStartMark().getLine());
                }
                if (token.getTokenId() == Token.ID.StreamEnd) break;
            }
        } catch (RuntimeException ignored) {
            // 已扫描到的独立诊断注释仍可移除，块文本不属于注释 token。
        }
        String separator = raw.contains("\r\n") ? "\r\n" : "\n";
        List<String> output = new ArrayList<>();
        String[] lines = raw.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (owned.contains(i) && lines[i].stripLeading().startsWith("# " + MARKER)) continue;
            output.add(lines[i]);
        }
        return String.join(separator, output);
    }

    public static void report(Path file, FileType format, String expected, List<ConfigIssue> issues, boolean emit) {
        if (!emit) throw new IllegalStateException("Diagnostic writes disabled");
        try {
            if (format == FileType.YAML && expected != null && Files.exists(file)
                    && Files.readString(file).equals(expected)) {
                try {
                    writeAtomic(file, annotate(expected, issues));
                    return;
                } catch (RuntimeException ignored) {
                    // 原文件无法标注时，尝试不修改原文的独立诊断文件。
                }
            }
            Path report = sidecar(file);
            if (Files.exists(report) && !Files.readString(report).startsWith(HEADER)) {
                throw new IllegalStateException("Diagnostic path already contains a user file");
            }
            StringBuilder text = new StringBuilder(HEADER);
            text.append("file: ").append(safe(file.getFileName().toString())).append('\n');
            for (ConfigIssue issue : issues) text.append(safe(issue.key())).append(": ").append(safe(issue.message())).append('\n');
            writeAtomic(report, text.toString());
        } catch (IOException exception) { throw new IllegalStateException("Cannot write configuration diagnostics", exception); }
    }

    public static void clearSidecar(Path file) {
        Path sidecar = sidecar(file);
        try {
            if (Files.isRegularFile(sidecar) && Files.readString(sidecar).startsWith(HEADER)) Files.delete(sidecar);
        } catch (IOException ignored) {
            // 清理旧诊断不影响已成功加载的配置。
        }
    }

    public static void writeAtomic(Path file, String content) {
        Path temporary = null;
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".linlang-", ".tmp");
            Files.writeString(temporary, content);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) { throw new IllegalStateException("Cannot write configuration", exception); }
        finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public static String safe(String text) {
        return text.replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", " ");
    }
}
