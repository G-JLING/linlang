// linlang-core/src/main/java/io/linlang/filesystem/impl/DataServiceImpl.java
package io.linlang.filesystem.impl;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.linlang.filesystem.*;
import io.linlang.filesystem.annotations.*;
import io.linlang.filesystem.runtime.Binder;
import io.linlang.filesystem.types.DbType;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/** 最小可用：Hikari 连接池 + 反射建表 + 简易 Repository。 */
public final class DataServiceImpl implements DataService {
    private HikariDataSource ds;

    @Override
    public void init(DbType type, DbConfig cfg){
        HikariConfig hc = new HikariConfig();
        if (type == DbType.H2){
            hc.setJdbcUrl(cfg.url());
            hc.setUsername(cfg.user());
            hc.setPassword(cfg.pass());
            hc.setDriverClassName("org.h2.Driver");
        } else {
            hc.setJdbcUrl(cfg.url());
            hc.setUsername(cfg.user());
            hc.setPassword(cfg.pass());
            hc.setDriverClassName("com.mysql.cj.jdbc.Driver");
        }
        hc.setMaximumPoolSize(Math.max(4, cfg.poolSize()));
        hc.setMinimumIdle(Math.min(2, cfg.poolSize()));
        this.ds = new HikariDataSource(hc);
    }

    @Override public void migrate(){
        // 简化：由上层传入实体清单或扫描 classpath，这里假设上层会调用 repo() 时自动建表
    }

    @Override public <T> Repository<T, ?> repo(Class<T> entityType){
        Binder.BoundTable t = Binder.tableOf(entityType).orElseThrow(()->new IllegalArgumentException("@Table missing on "+entityType));
        ensureTable(entityType, t.name());
        return new RepositoryImpl<>(ds, entityType, t.name());
    }

    private <T> void ensureTable(Class<T> type, String table){
        // 生成最小 CREATE TABLE IF NOT EXISTS
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

        @Override public io.linlang.filesystem.Page<T> query(io.linlang.filesystem.QuerySpec spec){
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
                return new io.linlang.filesystem.Page<>(out, out.size(), spec.offset());
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
}