package audit.linlang.audit;

public interface Auditor {
    void emit(String event, Object... kv);
}
