package api.linlang.file.database.dto;

// linlang-called/src/main/java/io/linlang/file/Page.java

import java.util.List;

/** 分页结果。total 为当前页条数的简化实现；如需总数请扩展。 */
public final class Page<T> {
    private final List<T> items;
    private final int total;
    private final int offset;

    public Page(List<T> items, int total, int offset){
        this.items = items; this.total = total; this.offset = offset;
    }
    public List<T> items(){ return items; }
    public int total(){ return total; }
    public int offset(){ return offset; }
}