package core.linlang.database.impl;

import api.linlang.database.annotations.Column;
import api.linlang.database.annotations.Entity;
import api.linlang.database.annotations.Id;
import api.linlang.database.annotations.Transient;
import api.linlang.database.dto.Page;
import api.linlang.database.dto.QuerySpec;
import api.linlang.database.repo.Repository;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

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
}