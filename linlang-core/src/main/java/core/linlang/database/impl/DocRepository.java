package core.linlang.database.impl;

import api.linlang.database.annotations.Id;
import api.linlang.database.dto.Page;
import api.linlang.database.dto.QuerySpec;
import api.linlang.database.repo.Repository;
import api.linlang.database.types.DbType;
import core.linlang.file.runtime.TreeMapper;
import core.linlang.json.JsonCodec;
import core.linlang.yaml.YamlCodec;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

// —— 文档仓库（YAML/JSON） —— //
public final class DocRepository<T, ID> implements Repository<T, ID> {
    private final Path root;
    private final String table;
    private final DbType mode;
    private final Class<T> type;
    private final Field idField;

    DocRepository(Path root, String table, DbType mode, Class<T> type) {
        this.root = root;
        this.table = table;
        this.mode = mode;
        this.type = type;
        Field idF = null;
        for (Field f : type.getDeclaredFields()) {
            if (f.isAnnotationPresent(Id.class)) {
                f.setAccessible(true);
                idF = f;
                break;
            }
        }
        if (idF == null) throw new IllegalArgumentException(type + " requires @Id field for document store");
        this.idField = idF;
    }

    private Path file() {
        String ext = (mode == DbType.YAML ? ".yml" : ".json");
        return root.resolve(table + ext);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readAll() {
        Path f = file();
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try {
            String s = Files.readString(f);
            return mode == DbType.YAML ? YamlCodec.load(s) : JsonCodec.load(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void writeAll(Map<String, Object> m) {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            String out = (mode == DbType.YAML ? YamlCodec.dump(m) : JsonCodec.dump(m));
            Files.writeString(f, out);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public T save(T e) {
        try {
            Map<String, Object> doc = readAll();
            Object id = idField.get(e);
            if (id == null || (id instanceof Number && ((Number) id).longValue() == 0L)) {
                id = nextId(doc.keySet());
                // 回填生成的 id
                if (idField.getType() == Long.class || idField.getType() == long.class)
                    idField.set(e, ((Number) id).longValue());
                else if (idField.getType() == Integer.class || idField.getType() == int.class)
                    idField.set(e, ((Number) id).intValue());
                else if (idField.getType() == String.class) idField.set(e, String.valueOf(id));
            }
            String key = String.valueOf(id);
            Map<String, Object> row = new LinkedHashMap<>();
            // 利用 TreeMapper 做对象<->Map 映射
            TreeMapper.export(e, row);
            doc.put(key, row);
            writeAll(doc);
            return e;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    public void deleteById(ID id) {
        Map<String, Object> doc = readAll();
        doc.remove(String.valueOf(id));
        writeAll(doc);
    }

    @Override
    public Optional<T> findById(ID id) {
        Map<String, Object> doc = readAll();
        Object row = doc.get(String.valueOf(id));
        if (!(row instanceof Map)) return Optional.empty();
        T obj = newInstance(type);
        TreeMapper.populate(obj, (Map<String, Object>) row);
        return Optional.of(obj);
    }

    @Override
    public List<T> findAll() {
        Map<String, Object> doc = readAll();
        List<T> out = new ArrayList<>();
        for (Object v : doc.values()) {
            if (v instanceof Map) {
                T obj = newInstance(type);
                TreeMapper.populate(obj, (Map<String, Object>) v);
                out.add(obj);
            }
        }
        return out;
    }

    @Override
    public Page<T> query(QuerySpec spec) {
        // 文档模式不支持 SQL 查询，简单返回全部
        List<T> all = findAll();
        return new Page<>(all, all.size(), 0);
    }

    private static Object nextId(Set<String> keys) {
        long max = 0;
        boolean numeric = true;
        for (String k : keys) {
            try {
                max = Math.max(max, Long.parseLong(k));
            } catch (NumberFormatException e) {
                numeric = false;
                break;
            }
        }
        if (numeric) return max + 1;
        return java.util.UUID.randomUUID().toString();
    }

    private static <X> X newInstance(Class<X> t) {
        try {
            return t.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}