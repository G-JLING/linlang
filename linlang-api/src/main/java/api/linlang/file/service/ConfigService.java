package api.linlang.file.service;

public interface ConfigService {
    <T> T bind(Class<T> type);             // 类 ↔ 文件 实例化并加载
    void save(Object config);
    void reload(Class<?> type);
}