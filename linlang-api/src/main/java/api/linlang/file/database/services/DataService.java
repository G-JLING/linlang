package api.linlang.file.database.services;

import api.linlang.file.database.config.DbConfig;
import api.linlang.file.database.repo.Repository;
import api.linlang.file.database.types.DbType;

/**
 * Database service facade used by plugins and higher-level modules.
 * <p>
 * This API no longer uses ServiceLoader/SPI discovery. Create the concrete
 * implementation directly in your runtime module (e.g. core) and pass it
 * around via dependency injection.
 * <p>
 * Typical lifecycle:
 * <ol>
 *   <li>Construct implementation</li>
 *   <li>{@link #init(DbType, DbConfig)} to open connections</li>
 *   <li>Obtain repositories via {@link #repo(Class)}</li>
 *   <li>Optionally {@link #migrate()} schemas</li>
 *   <li>Call {@link #flushAll()} on shutdown</li>
 * </ol>
 */
public interface DataService extends AutoCloseable {

    /**
     * Initialize connection pool / driver by database type and config.
     */
    void init(DbType type, DbConfig cfg);

    /**
     * Obtain a repository for the annotated entity type.
     * @param entityType entity class
     * @return repository bound to that entity
     */
    <T, ID> Repository<T, ID> repo(Class<T> entityType);

    /**
     * Perform pending schema migrations if supported by the implementation.
     * No-op if unsupported.
     */
    void migrate();

    /** Flush all pending changes to the underlying store. */
    void flushAll();

    /** Flush the repository of a specific entity type. */
    <T> void flushOf(Class<T> entityType);

    /**
     * Close the service. Default behaviour flushes all repositories.
     */
    @Override
    default void close() { flushAll(); }
}