package api.linlang.file.database.repo;

import api.linlang.file.database.dto.Page;
import api.linlang.file.database.dto.QuerySpec;
import api.linlang.file.database.services.DataService;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface Repository<T, ID> extends AutoCloseable {

    T save(T e);

    void deleteById(ID id);

    Optional<T> findById(ID id);

    List<T> findAll();

    Page<T> query(QuerySpec spec);

    long count();

    boolean existsById(ID id);

    void deleteAll();

    void saveAll(java.util.Collection<T> entities);

    java.util.stream.Stream<T> streamAll();

    default Optional<T> findOneWhere(String column, Object value) {
        return findAllWhere(column, value).stream().findFirst();
    }

    default void flush() { }

    @Override
    default void close() {  }

    default List<T> findAllWhere(String where, Object... params) {
        if (where != null && where.contains("=") && params.length == 1) {
            String field = where.split("=")[0].trim();
            Object expected = params[0];
            List<T> all = findAll();
            List<T> out = new ArrayList<>();
            for (T e : all) {
                try {
                    Field f = e.getClass().getDeclaredField(field);
                    f.setAccessible(true);
                    Object val = f.get(e);
                    if (Objects.equals(val, expected)) out.add(e);
                } catch (Exception ignore) {}
            }
            return out;
        }
        return findAll();
    }
}