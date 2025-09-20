package io.linlang.filesystem;

public interface AddonService {
    <T> T bind(Class<T> type);
    void save(Object addon);
    void reload(Class<?> type);
}