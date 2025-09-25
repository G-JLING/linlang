package api.linlang.database.services;

import api.linlang.database.config.DbConfig;
import api.linlang.database.repo.Repository;
import api.linlang.database.types.DbType;

public interface DataService {
    /** Initialize connection pool and driver by database type and config. */
    void init(DbType type, DbConfig cfg);

    /** Obtain a repository for the annotated entity type. */
    <T> Repository<T, ?> repo(Class<T> entityType);

    /** Scan and migrate tables if needed. Optional no-op for minimal impl. */
    void migrate();
}