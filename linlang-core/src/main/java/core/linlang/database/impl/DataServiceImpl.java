package core.linlang.database.impl;

import api.linlang.database.config.DbConfig;
import api.linlang.database.dto.Page;
import api.linlang.database.dto.QuerySpec;
import api.linlang.database.annotations.Entity;
import api.linlang.database.annotations.Transient;
import api.linlang.database.annotations.Column;
import api.linlang.database.annotations.Id;
import api.linlang.database.annotations.NotNull;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import api.linlang.database.repo.Repository;
import core.linlang.file.runtime.Binder;
import core.linlang.file.runtime.PathResolver;
import api.linlang.database.services.DataService;
import api.linlang.database.types.DbType;
import core.linlang.file.runtime.TreeMapper;
import core.linlang.yaml.YamlCodec;
import core.linlang.json.JsonCodec;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public final class DataServiceImpl implements DataService {
    private final Path dataDocRoot;
    private DbType mode = DbType.H2;
    private HikariDataSource ds;
    private final java.util.Set<Class<?>> registeredEntities = new java.util.LinkedHashSet<>();

    public DataServiceImpl() {
        this(() -> java.nio.file.Paths.get("./data"));
    }

    public DataServiceImpl(PathResolver resolver) {
        this.dataDocRoot = resolver.sub("data");
        try {
            Files.createDirectories(this.dataDocRoot);
        } catch (Exception ignore) {
        }
    }

    @Override
    public void init(DbType type, DbConfig cfg) {
        {
            this.mode = type;
            if (type == DbType.H2 || type == DbType.MYSQL) {
                HikariConfig hc = new HikariConfig();
                hc.setJdbcUrl(cfg.url());
                hc.setUsername(cfg.user());
                hc.setPassword(cfg.pass());
                hc.setDriverClassName(type == DbType.H2 ? "org.h2.Driver" : "com.mysql.cj.jdbc.Driver");
                hc.setMaximumPoolSize(Math.max(4, cfg.poolSize()));
                hc.setMinimumIdle(Math.min(2, cfg.poolSize()));
                this.ds = new HikariDataSource(hc);
            } else {
                // 文档模式（YAML/JSON）不需要数据源
                this.ds = null;
            }
        }
    }

    @Override
    public void migrate() {
        if (mode == DbType.YAML || mode == DbType.JSON) return; // 文档模式无 DDL
        for (Class<?> et : registeredEntities) {
            Binder.BoundTable t = Binder.tableOf(et).orElse(null);
            if (t == null) continue;
            ensureTable(et, t.name());
            try (Connection c = ds.getConnection()) {
                DatabaseMetaData md = c.getMetaData();
                Set<String> existing = new HashSet<>();
                try (ResultSet rs = md.getColumns(c.getCatalog(), null, t.name(), null)) {
                    while (rs.next()) existing.add(rs.getString("COLUMN_NAME"));
                }
                List<Col> cols = columns(et);
                for (Col col : cols) {
                    if (!existing.contains(col.name)) {
                        String add = "ALTER TABLE `" + t.name() + "` ADD COLUMN `" + col.name + "` " + col.sqlType;
                        try (Statement s = c.createStatement()) {
                            s.execute(add);
                        }
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public <T> Repository<T, ?> repo(Class<T> entityType) {
        Binder.BoundTable t = Binder.tableOf(entityType)
                .orElseThrow(() -> new IllegalArgumentException("@Table missing on " + entityType));
        registeredEntities.add(entityType);
        if (mode == DbType.YAML || mode == DbType.JSON) {
            return new DocRepository<>(dataDocRoot, t.name(), mode, entityType);
        }
        ensureTable(entityType, t.name());
        return new RepositoryImpl<>(ds, entityType, t.name());
    }

    private <T> void ensureTable(Class<T> type, String table) {
        if (mode == DbType.YAML || mode == DbType.JSON) return; // 文档模式不建表
        List<Col> cols = columns(type);
        String pk = cols.stream().filter(c -> c.id).map(c -> c.name).findFirst().orElse(null);
        String colDefs = cols.stream().map(Col::ddl).collect(Collectors.joining(", "));
        String ddl = "CREATE TABLE IF NOT EXISTS `" + table + "` (" + colDefs + (pk != null ? ", PRIMARY KEY(`" + pk + "`)" : "") + ")";
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(ddl);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    // —— 列映射 —— //
    private record Col(String name, String sqlType, boolean notNull, boolean id, boolean auto) {
        String ddl() {
            String base = "`" + name + "` " + sqlType + (notNull ? " NOT NULL" : "");
            if (id && auto) return base + " AUTO_INCREMENT";
            return base;
        }
    }

    private static <T> List<Col> columns(Class<T> type) {
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
            String sql = guessType(f, col);
            out.add(new Col(name, sql, notNull, id != null, auto));
        }
        return out;
    }

    private static String guessType(Field f, Column col) {
        Class<?> t = f.getType();
        int len = col == null ? 0 : col.length();
        if (t == Long.class || t == long.class) return "BIGINT";
        if (t == Integer.class || t == int.class) return "INT";
        if (t == Boolean.class || t == boolean.class) return "TINYINT(1)";
        if (t == Double.class || t == double.class) return "DOUBLE";
        if (t == Float.class || t == float.class) return "FLOAT";
        if (t == java.time.Instant.class) return "TIMESTAMP";
        if (t == String.class) {
            if (len > 0 && len <= 1024) return "VARCHAR(" + len + ")";
            return "TEXT";
        }
        return "TEXT"; // 简化：复杂类型由调用方自行序列化为 JSON 字符串
    }




    // —— 简单可读数据存取（YAML/JSON） —— //
    public Map<String, Object> loadYamlDoc(String relative) {
        Path f = resolveDoc(relative, ".yml");
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try {
            return YamlCodec.load(Files.readString(f));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void saveYamlDoc(String relative, Map<String, Object> doc) {
        Path f = resolveDoc(relative, ".yml");
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, YamlCodec.dump(doc));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> loadJsonDoc(String relative) {
        Path f = resolveDoc(relative, ".json");
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try {
            return JsonCodec.load(Files.readString(f));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void saveJsonDoc(String relative, Map<String, Object> doc) {
        Path f = resolveDoc(relative, ".json");
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, JsonCodec.dump(doc));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Path resolveDoc(String relative, String ext) {
        String rel = relative;
        if (rel.endsWith(".yml") || rel.endsWith(".yaml") || rel.endsWith(".json")) {
            // 保持用户给的扩展名
            return dataDocRoot.resolve(rel);
        }
        return dataDocRoot.resolve(rel + ext);
    }
}