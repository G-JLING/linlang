package api.linlang.file.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 指定配置文件的版本号。
 *
 * <p>此注解应用于类和字段。</p>
 *
 * @apiNote
 * Linlang 框架可根据版本号触发对应的 {@link api.linlang.file.implement.Migrator} 执行迁移。
 */
@Retention(RUNTIME)
@Target({TYPE})
public @interface ConfigVersion {
    /**
     * 当前配置文件版本号。
     */
    int value();
}