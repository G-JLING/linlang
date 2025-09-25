package api.linlang.database.config;

// linlang-called/src/main/java/io/linlang/file/DbConfig.java

/** 数据源配置。用在 DataService.init(...)。 */
public final class DbConfig {
    private final String url;
    private final String user;
    private final String pass;
    private final int poolSize;

    public DbConfig(String url, String user, String pass, int poolSize){
        this.url = url; this.user = user; this.pass = pass; this.poolSize = poolSize;
    }

    public String url(){ return url; }
    public String user(){ return user; }
    public String pass(){ return pass; }
    public int poolSize(){ return poolSize; }

    /** 便捷构造。 */
    public static DbConfig of(String url, String user, String pass, int poolSize){
        return new DbConfig(url, user, pass, poolSize);
    }
}