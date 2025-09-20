package io.linlang.filesystem;

import java.util.List;
import java.util.Optional;

public interface Repository<T, ID> {
    T save(T e);
    void deleteById(ID id);
    Optional<T> findById(ID id);
    List<T> findAll();
    Page<T> query(QuerySpec spec);
}