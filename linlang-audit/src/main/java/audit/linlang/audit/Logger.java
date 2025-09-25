package audit.linlang.audit;

public interface Logger {
    void debug(String msg, Object... kv);
    void info(String msg, Object... kv);
    void warn(String msg, Object... kv);
    void error(String msg, Throwable t, Object... kv);
}