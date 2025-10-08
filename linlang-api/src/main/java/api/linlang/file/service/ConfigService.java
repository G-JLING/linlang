package api.linlang.file.service;

public interface ConfigService {
    <T> T bind(Class<T> type);             // 类 ↔ 文件 实例化并加载

    <T> void save(Class<T> type, T config);
    void saveAll();
}