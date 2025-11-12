package core.linlang.database.impl;

import api.linlang.file.database.annotations.Column;
import api.linlang.file.database.annotations.Entity;
import api.linlang.file.database.annotations.Id;
import api.linlang.file.database.annotations.Transient;
import api.linlang.file.database.dto.Page;
import api.linlang.file.database.dto.QuerySpec;
import api.linlang.file.database.repo.Repository;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class RepositoryImpl<T, ID> implements Repository<T, ID> {
    private final DataSource ds;
    private final Class<T> type;
    private final String table;
    private final List<Field> fields;      // 可持久化字段
    private final Field idField;
    private final Map<Field, String> colName;

    RepositoryImpl(DataSource ds, Class<T> type, String table) {
        this.ds = ds;
        this.type = type;
        this.table = table;
        List<Field> tmp = new ArrayList<>();
        Map<Field, String> names = new LinkedHashMap<>();
        Field idF = null;
        boolean implicit = type.isAnnotationPresent(Entity.class);

        for (Field f : type.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (java.lang.reflect.Modifier.isStatic(mod) || java.lang.reflect.Modifier.isTransient(mod)) continue;

            Column c = f.getAnnotation(Column.class);
            Id id = f.getAnnotation(Id.class);

            boolean excludedByTransient = f.isAnnotationPresent(Transient.class);
            boolean include =
                    (c != null) || (id != null) || (implicit && !excludedByTransient);

            if (!include) continue;

            f.setAccessible(true);
            if (id != null) idF = f;
            String name = (c != null && !c.name().isEmpty()) ? c.name() : f.getName();
            tmp.add(f);
            names.put(f, name);
        }
        this.fields = tmp;
        this.colName = names;
        this.idField = idF;
    }

    @Override
    public T save(T e) {
        try {
            Object idVal = idField == null ? null : idField.get(e);
            if (idVal == null || (idVal instanceof Number && ((Number) idVal).longValue() == 0L)) {
                // insert
                String cols = fields.stream().filter(f -> f != idField).map(colName::get).collect(Collectors.joining(","));
                String qs = fields.stream().filter(f -> f != idField).map(f -> "?").collect(Collectors.joining(","));
                String sql = "INSERT INTO `" + table + "`(" + cols + ") VALUES(" + qs + ")";
                try (Connection c = ds.getConnection();
                     PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                    int i = 1;
                    for (Field f : fields) {
                        if (f == idField) continue;
                        ps.setObject(i++, toDb(f.get(e)));
                    }
                    ps.executeUpdate();
                    if (idField != null) {
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            if (rs.next()) {
                                Object gen = rs.getObject(1);
                                if (idField.getType() == Long.class || idField.getType() == long.class)
                                    idField.set(e, ((Number) gen).longValue());
                                else idField.set(e, gen);
                            }
                        }
                    }
                }
            } else {
                // update
                String sets = fields.stream().filter(f -> f != idField)
                        .map(f -> "`" + colName.get(f) + "`=?").collect(Collectors.joining(","));
                String sql = "UPDATE `" + table + "` SET " + sets + " WHERE `" + colName.get(idField) + "`=?";
                try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                    int i = 1;
                    for (Field f : fields) {
                        if (f == idField) continue;
                        ps.setObject(i++, toDb(f.get(e)));
                    }
                    ps.setObject(i, idField.get(e));
                    ps.executeUpdate();
                }
            }
            return e;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }


    @Override
    public void deleteById(ID id) {
        String sql = "DELETE FROM `" + table + "` WHERE `" + colName.get(idField) + "`=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<T> findById(ID id) {
        String cols = fields.stream().map(f -> "`" + colName.get(f) + "`").collect(Collectors.joining(","));
        String sql = "SELECT " + cols + " FROM `" + table + "` WHERE `" + colName.get(idField) + "`=? LIMIT 1";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(fromRow(rs));
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public java.util.List<T> findAll() {
        String cols = fields.stream().map(f -> "`" + colName.get(f) + "`").collect(Collectors.joining(","));
        String sql = "SELECT " + cols + " FROM `" + table + "`";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(fromRow(rs));
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Page<T> query(QuerySpec spec) {
        // 极简：where 原样拼接 + limit/offset
        String cols = fields.stream().map(f -> "`" + colName.get(f) + "`").collect(Collectors.joining(","));
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM `").append(table).append("`");
        if (spec.where() != null && !spec.where().isBlank()) sql.append(" WHERE ").append(spec.where());
        if (spec.orderBy() != null && !spec.orderBy().isBlank()) sql.append(" ORDER BY ").append(spec.orderBy());
        if (spec.limit() > 0) sql.append(" LIMIT ").append(spec.limit());
        if (spec.offset() > 0) sql.append(" OFFSET ").append(spec.offset());
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            int i = 1;
            for (Object p : spec.params()) ps.setObject(i++, p);
            List<T> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(fromRow(rs));
            }
            return new Page<>(out, out.size(), spec.offset());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private T fromRow(ResultSet rs) throws Exception {
        T obj = type.getDeclaredConstructor().newInstance();
        int idx = 1;
        for (Field f : fields) {
            Object v = rs.getObject(idx++);
            if (v != null && f.getType() == java.time.Instant.class && v instanceof Timestamp)
                v = ((Timestamp) v).toInstant();
            f.set(obj, v);
        }
        return obj;
    }

    private Object toDb(Object v) {
        if (v instanceof java.time.Instant) return Timestamp.from((java.time.Instant) v);
        return v;
    }

    /**
     * 返回表中记录数
     */
    public long count() {
        String sql = "SELECT COUNT(*) FROM `" + table + "`";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getLong(1);
            return 0L;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 检查指定 ID 是否存在
     */
    public boolean existsById(ID id) {
        String sql = "SELECT 1 FROM `" + table + "` WHERE `" + colName.get(idField) + "`=? LIMIT 1";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 按指定列查找单条记录
     */
    public Optional<T> findOneWhere(String column, Object value) {
        String cols = fields.stream().map(f -> "`" + colName.get(f) + "`").collect(Collectors.joining(","));
        String sql = "SELECT " + cols + " FROM `" + table + "` WHERE `" + column + "`=? LIMIT 1";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(fromRow(rs));
                return Optional.empty();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 按自定义 WHERE 条件查询多条记录
     */
    public List<T> findAllWhere(String where, Object... params) {
        String cols = fields.stream().map(f -> "`" + colName.get(f) + "`").collect(Collectors.joining(","));
        String sql = "SELECT " + cols + " FROM `" + table + "`";
        if (where != null && !where.isBlank()) {
            sql += " WHERE " + where;
        }
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (params != null) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(fromRow(rs));
                return out;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 清空表
     */
    public void deleteAll() {
        String sql = "DELETE FROM `" + table + "`";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 批量保存（事务插入或更新）
     *
     * @return
     */
    public void saveAll(Collection<T> entities) {
        if (entities == null || entities.isEmpty()) return;
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (T e : entities) {
                    save(e);
                }
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * 返回流式结果（注意使用 try-with-resources 时消费完成）
     */
    public Stream<T> streamAll() {
        String cols = fields.stream().map(f -> "`" + colName.get(f) + "`").collect(Collectors.joining(","));
        String sql = "SELECT " + cols + " FROM `" + table + "`";
        try {
            Connection c = ds.getConnection();
            PreparedStatement ps = c.prepareStatement(sql);
            ResultSet rs = ps.executeQuery();
            Iterator<T> iterator = new Iterator<T>() {
                boolean hasNext = false;
                boolean computed = false;

                @Override
                public boolean hasNext() {
                    if (!computed) {
                        try {
                            hasNext = rs.next();
                        } catch (SQLException e) {
                            throw new RuntimeException(e);
                        }
                        computed = true;
                    }
                    return hasNext;
                }

                @Override
                public T next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    computed = false;
                    try {
                        return fromRow(rs);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };
            Spliterator<T> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL);
            // Use onClose to close resources when stream is closed
            return StreamSupport.stream(spliterator, false)
                    .onClose(() -> {
                        try {
                            rs.close();
                        } catch (SQLException ignored) {}
                        try {
                            ps.close();
                        } catch (SQLException ignored) {}
                        try {
                            c.close();
                        } catch (SQLException ignored) {}
                    });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}