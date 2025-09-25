package audit.linlang.audit;

// linlang-called/src/main/java/io/linlang/called/Auditor.java
public interface Auditor {
    void emit(String event, Object... kv);
}
