package core.linlang.file.text;

import api.linlang.file.file.LangService;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 在消费文本时查询所属语言服务，同一引用错误在重载前只报告一次。
 */
public final class ConfigTextResolver {
    private final Supplier<LangService> language;
    private final Consumer<String> report;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    public ConfigTextResolver(Supplier<LangService> language, Consumer<String> report) {
        this.language = language;
        this.report = report;
    }

    public void reset() {
        reported.clear();
    }

    public String text(ConfigText source) {
        return (String) resolve(source);
    }

    @SuppressWarnings("unchecked")
    public List<String> lines(ConfigText source) {
        return (List<String>) resolve(source);
    }

    private Object resolve(ConfigText source) {
        if (source.alias() == null) return source.fallback();
        String reference = source.alias() + ":" + source.key();
        try {
            LangService service = language.get();
            Object value = service == null ? null : service.lookup(source.alias(), source.key());
            return ConfigText.checked(value, source.lines());
        } catch (RuntimeException exception) {
            String issue = reference + (source.lines() ? " (string list)" : " (string)");
            if (reported.add(issue)) report.accept(issue);
            return source.fallback();
        }
    }
}
