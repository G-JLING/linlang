package core.linlang.database.impl;

import api.linlang.database.config.DbConfig;
import api.linlang.database.dto.Page;
import api.linlang.database.dto.QuerySpec;
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

/** 最小可用：Hikari 连接池 + 反射建表 + 简易 Repository。 */
public final class DataServiceImpl implements DataService {
    private final Path dataDocRoot;
    private DbType mode = DbType.H2;
    private HikariDataSource ds;
    private final java.util.Set<Class<?>> registeredEntities = new java.util.LinkedHashSet<>();

    public DataServiceImpl(){
        this(() -> java.nio.file.Paths.get("./data"));
    }
    public DataServiceImpl(PathResolver resolver){
        this.dataDocRoot = resolver.sub("data");
        try { Files.createDirectories(this.dataDocRoot); } catch (Exception ignore) {}
    }

    @Override
    public void init(DbType type, DbConfig cfg){
        {
            this.mode = type;
            if (type == DbType.H2 || type == DbType.MYSQL){
                HikariConfig hc = new HikariConfig();
                hc.setJdbcUrl(cfg.url());
                hc.setUsername(cfg.user());
                hc.setPassword(cfg.pass());
                hc.setDriverClassName(type==DbType.H2? "org.h2.Driver" : "com.mysql.cj.jdbc.Driver");
                hc.setMaximumPoolSize(Math.max(4, cfg.poolSize()));
                hc.setMinimumIdle(Math.min(2, cfg.poolSize()));
                this.ds = new HikariDataSource(hc);
            } else {
                // 文档模式（YAML/JSON）不需要数据源
                this.ds = null;
            }
        }
    }

    @Override public void migrate(){
        if (mode == DbType.YAML || mode == DbType.JSON) return; // 文档模式无 DDL
        for (Class<?> et : registeredEntities){
            Binder.BoundTable t = Binder.tableOf(et).orElse(null);
            if (t == null) continue;
            ensureTable(et, t.name());
            try (Connection c = ds.getConnection()){
                DatabaseMetaData md = c.getMetaData();
                Set<String> existing = new HashSet<>();
                try (ResultSet rs = md.getColumns(c.getCatalog(), null, t.name(), null)){
                    while (rs.next()) existing.add(rs.getString("COLUMN_NAME"));
                }
                List<Col> cols = columns(et);
                for (Col col : cols){
                    if (!existing.contains(col.name)){
                        String add = "ALTER TABLE `"+t.name()+"` ADD COLUMN `"+col.name+"` "+col.sqlType;
                        try (Statement s = c.createStatement()){ s.execute(add); }
                    }
                }
            } catch (SQLException e){ throw new RuntimeException(e); }
        }
    }

    @Override public <T> Repository<T, ?> repo(Class<T> entityType){
        Binder.BoundTable t = Binder.tableOf(entityType)
                .orElseThrow(() -> new IllegalArgumentException("@Table missing on "+entityType));
        registeredEntities.add(entityType);
        if (mode == DbType.YAML || mode == DbType.JSON){
            return new DocRepository<>(dataDocRoot, t.name(), mode, entityType);
        }
        ensureTable(entityType, t.name());
        return new RepositoryImpl<>(ds, entityType, t.name());
    }

    private <T> void ensureTable(Class<T> type, String table){
        if (mode == DbType.YAML || mode == DbType.JSON) return; // 文档模式不建表
        List<Col> cols = columns(type);
        String pk = cols.stream().filter(c->c.id).map(c->c.name).findFirst().orElse(null);
        String colDefs = cols.stream().map(Col::ddl).collect(Collectors.joining(", "));
        String ddl = "CREATE TABLE IF NOT EXISTS `"+table+"` ("+colDefs+(pk!=null? ", PRIMARY KEY(`"+pk+"`)":"")+")";
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()){ s.execute(ddl); }
        catch (SQLException e){ throw new RuntimeException(e); }
    }

    // —— 列映射 —— //
    private record Col(String name, String sqlType, boolean notNull, boolean id, boolean auto){
        String ddl(){
            String base = "`"+name+"` "+sqlType+(notNull? " NOT NULL":"");
            if (id && auto) return base + " AUTO_INCREMENT";
            return base;
        }
    }

    private static <T> List<Col> columns(Class<T> type){
        List<Col> out = new ArrayList<>();
        for (Field f: type.getDeclaredFields()){
            Column col = f.getAnnotation(Column.class);
            Id id = f.getAnnotation(Id.class);
            if (col == null && id == null) continue;
            String name = col!=null && !col.name().isEmpty()? col.name(): f.getName();
            boolean notNull = f.isAnnotationPresent(NotNull.class) || (col!=null && !col.nullable());
            boolean auto = id!=null && id.auto();
            String sql = guessType(f, col);
            out.add(new Col(name, sql, notNull, id!=null, auto));
        }
        return out;
    }

    private static String guessType(Field f, Column col){
        Class<?> t = f.getType();
        int len = col==null?0:col.length();
        if (t==Long.class || t==long.class) return "BIGINT";
        if (t==Integer.class || t==int.class) return "INT";
        if (t==Boolean.class || t==boolean.class) return "TINYINT(1)";
        if (t==Double.class || t==double.class) return "DOUBLE";
        if (t==Float.class || t==float.class) return "FLOAT";
        if (t==java.time.Instant.class) return "TIMESTAMP";
        if (t==String.class){
            if (len>0 && len<=1024) return "VARCHAR("+len+")";
            return "TEXT";
        }
        return "TEXT"; // 简化：复杂类型由调用方自行序列化为 JSON 字符串
    }

    // —— 简易 Repository 实现 —— //
    private static final class RepositoryImpl<T, ID> implements Repository<T, ID> {
        private final DataSource ds;
        private final Class<T> type;
        private final String table;
        private final List<Field> fields;      // 可持久化字段
        private final Field idField;
        private final Map<Field,String> colName;

        RepositoryImpl(DataSource ds, Class<T> type, String table){
            this.ds = ds; this.type=type; this.table=table;
            List<Field> tmp = new ArrayList<>();
            Map<Field,String> names = new LinkedHashMap<>();
            Field idF=null;
            for (Field f: type.getDeclaredFields()){
                Column c = f.getAnnotation(Column.class);
                Id id = f.getAnnotation(Id.class);
                if (c==null && id==null) continue;
                f.setAccessible(true);
                if (id!=null) idF=f;
                String name = (c!=null && !c.name().isEmpty())? c.name(): f.getName();
                tmp.add(f); names.put(f,name);
            }
            this.fields = tmp;
            this.colName = names;
            this.idField = idF;
        }

        @Override public T save(T e){
            try {
                Object idVal = idField==null? null : idField.get(e);
                if (idVal == null || (idVal instanceof Number && ((Number)idVal).longValue()==0L)){
                    // insert
                    String cols = fields.stream().filter(f->f!=idField).map(colName::get).collect(Collectors.joining(","));
                    String qs   = fields.stream().filter(f->f!=idField).map(f->"?").collect(Collectors.joining(","));
                    String sql = "INSERT INTO `"+table+"`("+cols+") VALUES("+qs+")";
                    try (Connection c = ds.getConnection();
                         PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)){
                        int i=1;
                        for (Field f: fields){ if (f==idField) continue; ps.setObject(i++, toDb(f.get(e))); }
                        ps.executeUpdate();
                        if (idField!=null){
                            try (ResultSet rs = ps.getGeneratedKeys()){
                                if (rs.next()){
                                    Object gen = rs.getObject(1);
                                    if (idField.getType()==Long.class || idField.getType()==long.class)
                                        idField.set(e, ((Number)gen).longValue());
                                    else idField.set(e, gen);
                                }
                            }
                        }
                    }
                } else {
                    // update
                    String sets = fields.stream().filter(f->f!=idField)
                            .map(f->"`"+colName.get(f)+"`=?").collect(Collectors.joining(","));
                    String sql = "UPDATE `"+table+"` SET "+sets+" WHERE `"+colName.get(idField)+"`=?";
                    try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)){
                        int i=1;
                        for (Field f: fields){ if (f==idField) continue; ps.setObject(i++, toDb(f.get(e))); }
                        ps.setObject(i, idField.get(e));
                        ps.executeUpdate();
                    }
                }
                return e;
            } catch (Exception ex){ throw new RuntimeException(ex); }
        }

        @Override public void deleteById(ID id){
            String sql = "DELETE FROM `"+table+"` WHERE `"+colName.get(idField)+"`=?";
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)){
                ps.setObject(1, id); ps.executeUpdate();
            } catch (SQLException e){ throw new RuntimeException(e); }
        }

        @Override public Optional<T> findById(ID id){
            String cols = fields.stream().map(f->"`"+colName.get(f)+"`").collect(Collectors.joining(","));
            String sql = "SELECT "+cols+" FROM `"+table+"` WHERE `"+colName.get(idField)+"`=? LIMIT 1";
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)){
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()){
                    if (rs.next()) return Optional.of(fromRow(rs));
                    return Optional.empty();
                }
            } catch (Exception e){ throw new RuntimeException(e); }
        }

        @Override public java.util.List<T> findAll(){
            String cols = fields.stream().map(f->"`"+colName.get(f)+"`").collect(Collectors.joining(","));
            String sql = "SELECT "+cols+" FROM `"+table+"`";
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()){
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(fromRow(rs));
                return out;
            } catch (Exception e){ throw new RuntimeException(e); }
        }

        @Override public Page<T> query(QuerySpec spec){
            // 极简：where 原样拼接 + limit/offset
            String cols = fields.stream().map(f->"`"+colName.get(f)+"`").collect(Collectors.joining(","));
            StringBuilder sql = new StringBuilder("SELECT ").append(cols).append(" FROM `").append(table).append("`");
            if (spec.where()!=null && !spec.where().isBlank()) sql.append(" WHERE ").append(spec.where());
            if (spec.orderBy()!=null && !spec.orderBy().isBlank()) sql.append(" ORDER BY ").append(spec.orderBy());
            if (spec.limit()>0) sql.append(" LIMIT ").append(spec.limit());
            if (spec.offset()>0) sql.append(" OFFSET ").append(spec.offset());
            try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())){
                int i=1; for (Object p: spec.params()) ps.setObject(i++, p);
                List<T> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()){ while (rs.next()) out.add(fromRow(rs)); }
                return new Page<>(out, out.size(), spec.offset());
            } catch (Exception e){ throw new RuntimeException(e); }
        }

        private T fromRow(ResultSet rs) throws Exception {
            T obj = type.getDeclaredConstructor().newInstance();
            int idx=1;
            for (Field f: fields){
                Object v = rs.getObject(idx++);
                if (v != null && f.getType()==java.time.Instant.class && v instanceof Timestamp)
                    v = ((Timestamp)v).toInstant();
                f.set(obj, v);
            }
            return obj;
        }

        private Object toDb(Object v){
            if (v instanceof java.time.Instant) return Timestamp.from((java.time.Instant) v);
            return v;
        }
    }

    // —— 文档仓库（YAML/JSON） —— //
    private static final class DocRepository<T, ID> implements Repository<T, ID> {
        private final Path root;
        private final String table;
        private final DbType mode;
        private final Class<T> type;
        private final Field idField;

        DocRepository(Path root, String table, DbType mode, Class<T> type){
            this.root = root; this.table = table; this.mode = mode; this.type = type;
            Field idF = null;
            for (Field f: type.getDeclaredFields()){
                if (f.isAnnotationPresent(Id.class)){ f.setAccessible(true); idF=f; break; }
            }
            if (idF == null) throw new IllegalArgumentException(type+" requires @Id field for document store");
            this.idField = idF;
        }

        private Path file(){
            String ext = (mode==DbType.YAML? ".yml" : ".json");
            return root.resolve(table + ext);
        }
        @SuppressWarnings("unchecked")
        private Map<String,Object> readAll(){
            Path f = file();
            if (!Files.exists(f)) return new LinkedHashMap<>();
            try {
                String s = Files.readString(f);
                return mode==DbType.YAML? YamlCodec.load(s) : JsonCodec.load(s);
            } catch (Exception e){ throw new RuntimeException(e); }
        }
        private void writeAll(Map<String,Object> m){
            Path f = file();
            try {
                Files.createDirectories(f.getParent());
                String out = (mode==DbType.YAML? YamlCodec.dump(m) : JsonCodec.dump(m));
                Files.writeString(f, out);
            } catch (Exception e){ throw new RuntimeException(e); }
        }

        @Override public T save(T e){
            try {
                Map<String,Object> doc = readAll();
                Object id = idField.get(e);
                if (id == null || (id instanceof Number && ((Number)id).longValue()==0L)){
                    id = nextId(doc.keySet());
                    // 回填生成的 id
                    if (idField.getType()==Long.class || idField.getType()==long.class) idField.set(e, ((Number)id).longValue());
                    else if (idField.getType()==Integer.class || idField.getType()==int.class) idField.set(e, ((Number)id).intValue());
                    else if (idField.getType()==String.class) idField.set(e, String.valueOf(id));
                }
                String key = String.valueOf(id);
                Map<String,Object> row = new LinkedHashMap<>();
                // 利用 TreeMapper 做对象<->Map 映射
                TreeMapper.export(e, row);
                doc.put(key, row);
                writeAll(doc);
                return e;
            } catch (Exception ex){ throw new RuntimeException(ex); }
        }

        @Override public void deleteById(ID id){
            Map<String,Object> doc = readAll();
            doc.remove(String.valueOf(id));
            writeAll(doc);
        }

        @Override public Optional<T> findById(ID id){
            Map<String,Object> doc = readAll();
            Object row = doc.get(String.valueOf(id));
            if (!(row instanceof Map)) return Optional.empty();
            T obj = newInstance(type);
            TreeMapper.populate(obj, (Map<String,Object>) row);
            return Optional.of(obj);
        }

        @Override public List<T> findAll(){
            Map<String,Object> doc = readAll();
            List<T> out = new ArrayList<>();
            for (Object v : doc.values()){
                if (v instanceof Map){
                    T obj = newInstance(type);
                    TreeMapper.populate(obj, (Map<String,Object>) v);
                    out.add(obj);
                }
            }
            return out;
        }

        @Override public Page<T> query(QuerySpec spec){
            // 文档模式不支持 SQL 查询，简单返回全部
            List<T> all = findAll();
            return new Page<>(all, all.size(), 0);
        }

        private static Object nextId(Set<String> keys){
            long max = 0;
            boolean numeric = true;
            for (String k: keys){
                try { max = Math.max(max, Long.parseLong(k)); }
                catch (NumberFormatException e){ numeric = false; break; }
            }
            if (numeric) return max + 1;
            return java.util.UUID.randomUUID().toString();
        }

        private static <X> X newInstance(Class<X> t){
            try { return t.getDeclaredConstructor().newInstance(); }
            catch (Exception e){ throw new RuntimeException(e); }
        }
    }

    // —— 简单可读数据存取（YAML/JSON） —— //
    public Map<String,Object> loadYamlDoc(String relative){
        Path f = resolveDoc(relative, ".yml");
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try { return YamlCodec.load(Files.readString(f)); } catch (Exception e){ throw new RuntimeException(e); }
    }
    public void saveYamlDoc(String relative, Map<String,Object> doc){
        Path f = resolveDoc(relative, ".yml");
        try { Files.createDirectories(f.getParent()); Files.writeString(f, YamlCodec.dump(doc)); }
        catch (Exception e){ throw new RuntimeException(e); }
    }
    public Map<String,Object> loadJsonDoc(String relative){
        Path f = resolveDoc(relative, ".json");
        if (!Files.exists(f)) return new LinkedHashMap<>();
        try { return JsonCodec.load(Files.readString(f)); } catch (Exception e){ throw new RuntimeException(e); }
    }
    public void saveJsonDoc(String relative, Map<String,Object> doc){
        Path f = resolveDoc(relative, ".json");
        try { Files.createDirectories(f.getParent()); Files.writeString(f, JsonCodec.dump(doc)); }
        catch (Exception e){ throw new RuntimeException(e); }
    }

    private Path resolveDoc(String relative, String ext){
        String rel = relative;
        if (rel.endsWith(".yml") || rel.endsWith(".yaml") || rel.endsWith(".json")){
            // 保持用户给的扩展名
            return dataDocRoot.resolve(rel);
        }
        return dataDocRoot.resolve(rel + ext);
    }
}