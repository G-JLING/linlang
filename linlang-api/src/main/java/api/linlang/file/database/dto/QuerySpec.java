package api.linlang.file.database.dto;

// linlang-called/src/main/java/io/linlang/file/QuerySpec.java

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 极简查询规约：直接拼接 where/orderBy，带参数与分页。 */
public final class QuerySpec {
    private String where;
    private final List<Object> params = new ArrayList<>();
    private String orderBy;
    private int limit;
    private int offset;

    public static QuerySpec of(){ return new QuerySpec(); }

    public QuerySpec where(String where){ this.where = where; return this; }
    public QuerySpec param(Object p){ this.params.add(p); return this; }
    public QuerySpec params(List<?> ps){ this.params.addAll(ps); return this; }
    public QuerySpec orderBy(String orderBy){ this.orderBy = orderBy; return this; }
    public QuerySpec limit(int limit){ this.limit = limit; return this; }
    public QuerySpec offset(int offset){ this.offset = offset; return this; }

    public String where(){ return where; }
    public List<Object> params(){ return Collections.unmodifiableList(params); }
    public String orderBy(){ return orderBy; }
    public int limit(){ return limit; }
    public int offset(){ return offset; }
}