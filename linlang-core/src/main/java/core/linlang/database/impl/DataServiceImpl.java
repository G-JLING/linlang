package core.linlang.database.impl;

import api.linlang.audit.LinAudit;
import api.linlang.audit.LinLog;
import api.linlang.file.database.DataService;
import api.linlang.file.database.annotations.Entity;
import api.linlang.file.database.annotations.Transient;
import api.linlang.file.database.annotations.Column;
import api.linlang.file.database.annotations.Id;
import api.linlang.file.database.annotations.Index;
import api.linlang.file.database.annotations.Length;
import api.linlang.file.database.annotations.NotNull;
import api.linlang.file.file.path.PathResolver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import api.linlang.file.database.repo.Repository;
import core.linlang.audit.log.BuiltinLog;
import core.linlang.audit.problem.BuiltinProblemCatalog;
import core.linlang.file.runtime.Binder;


import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class DataServiceImpl implements DataService {
    private final Path dataDocRoot;
    private final LinAudit audit;
    private DbType mode;
    private HikariDataSource ds;
    private final java.util.Set<Class<?>> registeredEntities = new java.util.LinkedHashSet<>();
    private final Map<Class<?>, Repository<?, ?>> openRepos = new ConcurrentHashMap<>();


    public DataServiceImpl() {
        this(() -> java.nio.file.Paths.get("./data"));
    }

    public DataServiceImpl(PathResolver resolver) {
        this(resolver, null);
    }

    public DataServiceImpl(PathResolver resolver, Object owner) {
        this.audit = LinLog.forOwner(owner);
        this.dataDocRoot = resolver.sub("data");
        try {
            Files.createDirectories(this.dataDocRoot);
        } catch (Exception e) {
            audit.problem().report(BuiltinProblemCatalog.DATA_INITIALIZATION_FAILED, e,
                    "directory", this.dataDocRoot,
                    "operation", "create-directory");
            throw new IllegalStateException(BuiltinProblemCatalog.DATA_INITIALIZATION_FAILED, e);
        }
    }

    @Override
    public synchronized void init(DbType type, DbConfig cfg) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(cfg, "cfg");
        if (type != DbType.H2 && type != DbType.MYSQL) {
            throw new IllegalArgumentException("Unsupported DbType: " + type);
        }
        if (ds != null && !ds.isClosed()) {
            throw new IllegalStateException("DataService has already been initialized");
        }
        HikariDataSource created = null;
        try {
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(cfg.url());
            hc.setUsername(cfg.user());
            hc.setPassword(cfg.pass());
            hc.setDriverClassName(type == DbType.H2 ? "org.h2.Driver" : "com.mysql.cj.jdbc.Driver");
            hc.setMaximumPoolSize(cfg.poolSize());
            hc.setMinimumIdle(Math.min(2, cfg.poolSize()));
            created = new HikariDataSource(hc);
            this.mode = type;
            this.ds = created;
            audit.logger().file(BuiltinLog.DATABASE_INITIALIZED,
                    "type", type,
                    "url", sanitizedJdbcUrl(cfg.url()));
        } catch (RuntimeException exception) {
            if (created != null) created.close();
            audit.problem().report(BuiltinProblemCatalog.DATA_INITIALIZATION_FAILED, exception,
                    "type", type,
                    "operation", "connection-pool");
            throw new IllegalStateException(BuiltinProblemCatalog.DATA_INITIALIZATION_FAILED, exception);
        }
    }

    @Override
    public synchronized void migrate() {
        requireDataSource();
        for (Class<?> et : registeredEntities) {
            try {
                Binder.BoundTable t = Binder.tableOf(et).orElse(null);
                if (t == null) continue;
                ensureSchema(et, t);
            } catch (DataMappingException exception) {
                reportInvalidEntity(et, exception);
                throw exception;
            }
        }
    }

    @Override
    public synchronized <T, ID> Repository<T, ID> repo(Class<T> entityType) {
        Objects.requireNonNull(entityType, "entityType");
        try {
            HikariDataSource dataSource = requireDataSource();
            Binder.BoundTable t = Binder.tableOf(entityType)
                    .orElseThrow(() -> new DataMappingException("@Table missing on " + entityType));
            @SuppressWarnings("unchecked")
            Repository<T, ID> existing = (Repository<T, ID>) openRepos.get(entityType);
            if (existing != null) return existing;
            ensureSchema(entityType, t);
            Repository<T, ID> repository = new RepositoryImpl<>(
                    dataSource, entityType, t.name(), DatabaseDialect.from(mode), audit
            );
            registeredEntities.add(entityType);
            openRepos.put(entityType, repository);
            return repository;
        } catch (DataMappingException exception) {
            reportInvalidEntity(entityType, exception);
            throw exception;
        }
    }

    private void reportInvalidEntity(Class<?> entityType, DataMappingException exception) {
        audit.problem().report(
                BuiltinProblemCatalog.DATA_MAPPING_INVALID,
                exception,
                "entity", entityType == null ? "null" : entityType.getName()
        );
    }

    private <T> void ensureSchema(Class<T> type, Binder.BoundTable table) {
        HikariDataSource dataSource = requireDataSource();
        DatabaseDialect dialect = DatabaseDialect.from(mode);
        List<Col> cols = columns(type, dialect);
        validateEntity(type, table.name(), cols);
        Col primaryKey = cols.stream().filter(Col::id).findFirst().orElseThrow();
        String colDefs = cols.stream().map(col -> col.ddl(dialect)).collect(Collectors.joining(", "));
        String ddl = "CREATE TABLE IF NOT EXISTS " + DatabaseDialect.quote(table.name()) + " ("
                + colDefs + ", PRIMARY KEY(" + DatabaseDialect.quote(primaryKey.name()) + "))";
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(ddl);
            Set<String> existing = existingColumns(c, table.name());
            for (Col col : cols) {
                if (!existing.contains(DatabaseDialect.normalizeIdentifier(col.name()))) {
                    s.execute("ALTER TABLE " + DatabaseDialect.quote(table.name()) + " ADD COLUMN "
                            + col.ddl(dialect));
                }
            }
            ensurePrimaryKey(c, table.name(), primaryKey);
            ensureIndexes(c, type, table.name(), cols);
            if (!table.comment().isBlank()) {
                s.execute(dialect.tableComment(table.name(), table.comment()));
            }
        } catch (SQLException e) {
            audit.problem().report(BuiltinProblemCatalog.DATA_MIGRATION_FAILED, e,
                    "entity", type.getName(),
                    "table", table.name(),
                    "operation", "synchronize-schema");
            throw new IllegalStateException(BuiltinProblemCatalog.DATA_MIGRATION_FAILED, e);
        }
    }

    // —— 列映射 —— //
    private record Col(Field field, String name, String sqlType, boolean notNull, boolean id, boolean auto,
                       String defaultValue) {
        String ddl(DatabaseDialect dialect) {
            String base = DatabaseDialect.quote(name) + " " + sqlType + (notNull || id ? " NOT NULL" : "");
            if (!defaultValue.isBlank()) base += " DEFAULT " + safeDefault(defaultValue);
            if (id && auto) return dialect.autoIncrement(base);
            return base;
        }
    }

    private static <T> List<Col> columns(Class<T> type, DatabaseDialect dialect) {
        boolean implicit = type.isAnnotationPresent(Entity.class);

        List<Col> out = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            int mod = f.getModifiers();
            // 跳过 static / Java 关键字 transient 字段
            if (java.lang.reflect.Modifier.isStatic(mod) || java.lang.reflect.Modifier.isTransient(mod)) continue;

            var id = f.getAnnotation(Id.class);
            var col = f.getAnnotation(Column.class);

            boolean excludedByTransient = f.isAnnotationPresent(Transient.class);
            boolean include =
                    (col != null)            // 显式 @Column
                            || (id != null)             // 主键
                            || (implicit && !excludedByTransient); // @Entity 默认入库，除非 @Transient

            if (!include) continue;

            String name = (col != null && !col.name().isEmpty()) ? col.name() : f.getName();
            boolean notNull = f.isAnnotationPresent(NotNull.class) || (col != null && !col.nullable());
            boolean auto = id != null && id.auto();
            String sql = guessType(f, col, dialect);
            out.add(new Col(f, name, sql, notNull, id != null, auto,
                    col == null ? "" : col.defaultValue().trim()));
        }
        return out;
    }

    private static String guessType(Field f, Column col, DatabaseDialect dialect) {
        Class<?> t = f.getType();
        Length length = f.getAnnotation(Length.class);
        if (length != null && length.value() <= 0) {
            throw new DataMappingException("@Length must be positive on " + f);
        }
        int len = col != null && col.length() > 0 ? col.length() : length == null ? 0 : length.value();
        if (len < 0) throw new DataMappingException("Negative column length on " + f);
        if (t == Long.class || t == long.class) return "BIGINT";
        if (t == Integer.class || t == int.class) return "INT";
        if (t == Short.class || t == short.class) return "SMALLINT";
        if (t == Byte.class || t == byte.class) return "TINYINT";
        if (t == Boolean.class || t == boolean.class) return dialect.booleanType();
        if (t == Double.class || t == double.class) return "DOUBLE";
        if (t == Float.class || t == float.class) return "FLOAT";
        if (t == java.time.Instant.class) return "TIMESTAMP";
        if (t == java.util.UUID.class) return "VARCHAR(36)";
        if (t == byte[].class) return dialect.binaryType(len);
        if (t == Character.class || t == char.class) return "VARCHAR(1)";
        if (t.isEnum()) return "VARCHAR(" + (len > 0 ? len : 255) + ")";
        if (t == String.class) {
            if (len > 0) return "VARCHAR(" + len + ")";
            return "TEXT";
        }
        throw new DataMappingException("Unsupported database field type: " + f);
    }

    private static void validateEntity(Class<?> type, String table, List<Col> cols) {
        DatabaseDialect.quote(table);
        if (cols.isEmpty()) throw new DataMappingException("No persistent fields on " + type.getName());
        long ids = cols.stream().filter(Col::id).count();
        if (ids != 1) throw new DataMappingException("Exactly one @Id field is required on " + type.getName());
        Set<String> names = new HashSet<>();
        for (Col col : cols) {
            DatabaseDialect.quote(col.name());
            if (!names.add(DatabaseDialect.normalizeIdentifier(col.name()))) {
                throw new DataMappingException("Duplicate mapped column: " + col.name());
            }
            if (col.auto() && col.field().getType() != Long.class && col.field().getType() != long.class
                    && col.field().getType() != Integer.class && col.field().getType() != int.class) {
                throw new DataMappingException("Auto-generated @Id must be int or long on " + type.getName());
            }
        }
    }

    private static Set<String> existingColumns(Connection connection, String table) throws SQLException {
        Set<String> existing = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        String tablePattern = metadataTableName(connection, table);
        try (ResultSet result = metadata.getColumns(
                connection.getCatalog(), connection.getSchema(), tablePattern, null
        )) {
            while (result.next()) {
                existing.add(DatabaseDialect.normalizeIdentifier(result.getString("COLUMN_NAME")));
            }
        }
        return existing;
    }

    private static void ensurePrimaryKey(Connection connection, String table, Col expected) throws SQLException {
        Set<String> primaryKeys = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        String tablePattern = metadataTableName(connection, table);
        try (ResultSet result = metadata.getPrimaryKeys(
                connection.getCatalog(), connection.getSchema(), tablePattern
        )) {
            while (result.next()) {
                primaryKeys.add(DatabaseDialect.normalizeIdentifier(result.getString("COLUMN_NAME")));
            }
        }
        String expectedName = DatabaseDialect.normalizeIdentifier(expected.name());
        if (primaryKeys.isEmpty()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE " + DatabaseDialect.quote(table) + " ADD PRIMARY KEY ("
                        + DatabaseDialect.quote(expected.name()) + ")");
            }
        } else if (primaryKeys.size() != 1 || !primaryKeys.contains(expectedName)) {
            throw new SQLException("Existing primary key does not match @Id on " + table);
        }
    }

    private static void ensureIndexes(Connection connection, Class<?> type, String table, List<Col> cols)
            throws SQLException {
        Map<String, String> mapped = new HashMap<>();
        for (Col col : cols) {
            mapped.put(DatabaseDialect.normalizeIdentifier(col.field().getName()), col.name());
            mapped.put(DatabaseDialect.normalizeIdentifier(col.name()), col.name());
        }
        Set<String> existing = new HashSet<>();
        DatabaseMetaData metadata = connection.getMetaData();
        String tablePattern = metadataTableName(connection, table);
        try (ResultSet result = metadata.getIndexInfo(
                connection.getCatalog(), connection.getSchema(), tablePattern, false, false
        )) {
            while (result.next()) {
                String name = result.getString("INDEX_NAME");
                if (name != null) existing.add(DatabaseDialect.normalizeIdentifier(name));
            }
        }
        for (Col col : cols) {
            Index index = col.field().getAnnotation(Index.class);
            if (index == null) continue;
            List<String> indexColumns = new ArrayList<>();
            if (index.columns().length == 0) {
                indexColumns.add(col.name());
            } else {
                for (String requested : index.columns()) {
                    String resolved = mapped.get(DatabaseDialect.normalizeIdentifier(requested));
                    if (resolved == null) throw new DataMappingException("Unknown index column: " + requested);
                    indexColumns.add(resolved);
                }
            }
            String name = index.name().isBlank()
                    ? "idx_" + table + "_" + String.join("_", indexColumns)
                    : index.name();
            DatabaseDialect.quote(name);
            if (existing.add(DatabaseDialect.normalizeIdentifier(name))) {
                String columnsSql = indexColumns.stream().map(DatabaseDialect::quote).collect(Collectors.joining(", "));
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE INDEX " + DatabaseDialect.quote(name) + " ON "
                            + DatabaseDialect.quote(table) + " (" + columnsSql + ")");
                }
            }
        }
    }

    private static String safeDefault(String expression) {
        if (expression.indexOf(';') >= 0 || expression.contains("--") || expression.contains("/*")
                || expression.contains("*/") || expression.indexOf('\n') >= 0 || expression.indexOf('\r') >= 0) {
            throw new DataMappingException("Unsafe database default expression");
        }
        return expression;
    }

    private static String metadataTableName(Connection connection, String expected) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getTables(
                connection.getCatalog(), connection.getSchema(), null, new String[]{"TABLE"}
        )) {
            while (result.next()) {
                String actual = result.getString("TABLE_NAME");
                if (expected.equalsIgnoreCase(actual)) return actual;
            }
        }
        return expected;
    }

    private static String sanitizedJdbcUrl(String url) {
        return url.replaceAll("(?i)(password|passwd|pwd)=([^&;]+)", "$1=***");
    }


    @Override
    public void flushAll() {
        openRepos.forEach(this::flushRepository);
    }

    @Override
    public <T> void flushOf(Class<T> entityType) {
        Repository<?, ?> r = openRepos.get(entityType);
        if (r != null) {
            flushRepository(entityType, r);
        }
    }

    private void flushRepository(Class<?> entityType, Repository<?, ?> repository) {
        String table = tableName(entityType);
        try {
            repository.flush();
        } catch (Throwable exception) {
            audit.problem().report(BuiltinProblemCatalog.DATA_FLUSH_FAILED, exception,
                    "operation", "flush",
                    "entity", entityType.getName(),
                    "table", table);
        }
    }

    private static String tableName(Class<?> entityType) {
        return Binder.tableOf(entityType).map(Binder.BoundTable::name).orElse(entityType.getSimpleName());
    }

    @Override
    public synchronized void close() {
        closeResources(true);
    }

    private void closeResources(boolean flush) {
        if (flush) flushAll();
        for (Map.Entry<Class<?>, Repository<?, ?>> entry : openRepos.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Throwable exception) {
                audit.problem().report(
                        BuiltinProblemCatalog.DATA_RESOURCE_CLOSE_FAILED, exception,
                        "resource", "repository",
                        "entity", entry.getKey().getName(),
                        "table", tableName(entry.getKey())
                );
            }
        }
        openRepos.clear();
        HikariDataSource dataSource = this.ds;
        this.ds = null;
        if (dataSource != null) {
            try {
                dataSource.close();
                audit.logger().file(BuiltinLog.CONNECTION_POOL_CLOSED, "type", mode);
            } catch (RuntimeException exception) {
                audit.problem().report(BuiltinProblemCatalog.DATA_RESOURCE_CLOSE_FAILED, exception,
                        "resource", "connection-pool");
            }
        }
    }

    private HikariDataSource requireDataSource() {
        HikariDataSource dataSource = this.ds;
        if (dataSource == null || dataSource.isClosed()) {
            throw new IllegalStateException("DataService has not been initialized or is already closed");
        }
        return dataSource;
    }
}
