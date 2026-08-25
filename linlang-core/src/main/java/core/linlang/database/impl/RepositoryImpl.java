package core.linlang.database.impl;

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.file.database.annotations.Column;
import api.linlang.file.database.annotations.Entity;
import api.linlang.file.database.annotations.Id;
import api.linlang.file.database.annotations.Transient;
import api.linlang.file.database.dto.Page;
import api.linlang.file.database.dto.QuerySpec;
import api.linlang.file.database.repo.Repository;
import core.linlang.audit.problem.BuiltinProblemCatalog;

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
    private final LinAudit audit;

    RepositoryImpl(DataSource ds, Class<T> type, String table) {
        this(ds, type, table, LinLog.forOwner(null));
    }

    RepositoryImpl(DataSource ds, Class<T> type, String table, LinAudit audit) {
        this.ds = ds;
        this.type = type;
        this.table = table;
        this.audit = Objects.requireNonNull(audit, "audit");
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
        Objects.requireNonNull(e, "entity");
        try (Connection connection = ds.getConnection()) {
            return save(connection, e);
        } catch (Exception ex) {
            throw operationFailed("save", ex);
        }
    }

    private T save(Connection connection, T entity) throws Exception {
        Object idVal = idField == null ? null : idField.get(entity);
        if (idVal == null || (idVal instanceof Number && ((Number) idVal).longValue() == 0L)) {
            String cols = fields.stream().filter(f -> f != idField)
                    .map(f -> "`" + colName.get(f) + "`").collect(Collectors.joining(","));
            String qs = fields.stream().filter(f -> f != idField).map(f -> "?").collect(Collectors.joining(","));
            String sql = "INSERT INTO `" + table + "`(" + cols + ") VALUES(" + qs + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                int i = 1;
                for (Field field : fields) {
                    if (field == idField) continue;
                    statement.setObject(i++, toDb(field.get(entity)));
                }
                statement.executeUpdate();
                assignGeneratedId(entity, statement);
            }
            return entity;
        }

        String sets = fields.stream().filter(f -> f != idField)
                .map(f -> "`" + colName.get(f) + "`=?").collect(Collectors.joining(","));
        String sql = "UPDATE `" + table + "` SET " + sets + " WHERE `" + colName.get(idField) + "`=?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            for (Field field : fields) {
                if (field == idField) continue;
                statement.setObject(i++, toDb(field.get(entity)));
            }
            statement.setObject(i, idField.get(entity));
            statement.executeUpdate();
        }
        return entity;
    }

    private void assignGeneratedId(T entity, PreparedStatement statement) throws Exception {
        if (idField == null) return;
        try (ResultSet result = statement.getGeneratedKeys()) {
            if (!result.next()) return;
            Object generated = result.getObject(1);
            if (idField.getType() == Long.class || idField.getType() == long.class) {
                idField.set(entity, ((Number) generated).longValue());
            } else if (idField.getType() == Integer.class || idField.getType() == int.class) {
                idField.set(entity, ((Number) generated).intValue());
            } else {
                idField.set(entity, generated);
            }
        }
    }


    @Override
    public void deleteById(ID id) {
        String sql = "DELETE FROM `" + table + "` WHERE `" + colName.get(idField) + "`=?";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw operationFailed("delete-by-id", e);
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
            throw operationFailed("find-by-id", e);
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
            throw operationFailed("find-all", e);
        }
    }

    @Override
    public Page<T> query(QuerySpec spec) {
        // 极简：where 原样拼接 + limit/offset
        String cols = fields.stream().map(f -> "`" + colName.get(f) + "`").collect(Collectors.joining(","));
        StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM `").append(table).append("`");
        if (spec.where() != null && !spec.where().isBlank()) sql.append(" WHERE ").append(spec.where());
        if (spec.orderBy() != null && !spec.orderBy().isBlank()) {
            sql.append(" ORDER BY ").append(safeOrderBy(spec.orderBy()));
        }
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
            throw operationFailed("query", e);
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
            throw operationFailed("count", e);
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
            throw operationFailed("exists-by-id", e);
        }
    }

    /**
     * 按指定列查找单条记录
     */
    public Optional<T> findOneWhere(String column, Object value) {
        String mappedColumn = requireColumn(column);
        String cols = fields.stream().map(f -> "`" + colName.get(f) + "`").collect(Collectors.joining(","));
        String sql = "SELECT " + cols + " FROM `" + table + "` WHERE `" + mappedColumn + "`=? LIMIT 1";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(fromRow(rs));
                return Optional.empty();
            }
        } catch (Exception e) {
            throw operationFailed("find-one-where", e);
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
            throw operationFailed("find-all-where", e);
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
            throw operationFailed("delete-all", e);
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
                    save(c, Objects.requireNonNull(e, "entity"));
                }
                c.commit();
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (Exception ex) {
            throw operationFailed("save-all", ex);
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
                            throw operationFailed("stream-next", e);
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
                        throw operationFailed("stream-map-row", e);
                    }
                }
            };
            Spliterator<T> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL);
            // Use onClose to close resources when stream is closed
            return StreamSupport.stream(spliterator, false)
                    .onClose(() -> {
                        try {
                            rs.close();
                        } catch (SQLException exception) {
                            reportCloseFailure("result-set", exception);
                        }
                        try {
                            ps.close();
                        } catch (SQLException exception) {
                            reportCloseFailure("statement", exception);
                        }
                        try {
                            c.close();
                        } catch (SQLException exception) {
                            reportCloseFailure("connection", exception);
                        }
                    });
        } catch (SQLException e) {
            throw operationFailed("stream-open", e);
        }
    }

    private IllegalStateException operationFailed(String operation, Throwable cause) {
        audit.problem().report(BuiltinProblemCatalog.DATA_OPERATION_FAILED, cause,
                "operation", operation,
                "entity", type.getName(),
                "table", table);
        return new IllegalStateException(BuiltinProblemCatalog.DATA_OPERATION_FAILED, cause);
    }

    private void reportCloseFailure(String resource, SQLException cause) {
        audit.problem().report(BuiltinProblemCatalog.DATA_RESOURCE_CLOSE_FAILED, cause,
                "resource", resource,
                "entity", type.getName(),
                "table", table);
    }

    private String requireColumn(String column) {
        if (column == null || column.isBlank()) throw new IllegalArgumentException("column");
        String value = column.trim();
        if (!colName.containsValue(value)) {
            throw new IllegalArgumentException("Unknown mapped column: " + value);
        }
        return value;
    }

    private String safeOrderBy(String orderBy) {
        List<String> terms = new ArrayList<>();
        for (String rawTerm : orderBy.split(",")) {
            String[] parts = rawTerm.trim().split("\\s+");
            if (parts.length < 1 || parts.length > 2) {
                throw new IllegalArgumentException("Invalid orderBy term: " + rawTerm);
            }
            String column = requireColumn(parts[0]);
            String direction = "";
            if (parts.length == 2) {
                if (!parts[1].equalsIgnoreCase("ASC") && !parts[1].equalsIgnoreCase("DESC")) {
                    throw new IllegalArgumentException("Invalid orderBy direction: " + parts[1]);
                }
                direction = " " + parts[1].toUpperCase(Locale.ROOT);
            }
            terms.add("`" + column + "`" + direction);
        }
        return String.join(",", terms);
    }
}
