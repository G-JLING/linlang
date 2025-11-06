package api.linlang.file.database.services;

import api.linlang.file.PathResolver;
import api.linlang.file.database.config.DbConfig;
import api.linlang.file.database.repo.Repository;
import api.linlang.file.database.types.DbType;

import javax.print.Doc;
import java.util.Comparator;
import java.util.Optional;
import java.util.ServiceLoader;

public interface DataService extends AutoCloseable {

    void init(DbType type, DbConfig cfg);

    <T, ID> Repository<T, ID> repo(Class<T> entityType);

    void migrate();

    void flushAll();

    <T> void flushOf(Class<T> entityType);

    @Override
    default void close() { flushAll(); }

    interface Provider {
        String id();

        default int priority() { return 0; }

        DataService create(PathResolver resolver);
    }

    static DataService newService(PathResolver resolver) {
        ServiceLoader<Provider> loader = ServiceLoader.load(Provider.class);
        Optional<Provider> chosen = loader.stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(Provider::priority));
        if (chosen.isEmpty()) {
            throw new IllegalStateException("No DataService.Provider found via ServiceLoader");
        }
        return chosen.get().create(resolver);
    }

    static DataService newService(PathResolver resolver, String providerId) {
        ServiceLoader<Provider> loader = ServiceLoader.load(Provider.class);
        for (Provider p : loader) {
            if (p != null && p.id() != null && p.id().equalsIgnoreCase(providerId)) {
                return p.create(resolver);
            }
        }
        return newService(resolver);
    }
}