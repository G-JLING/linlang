package api.linlang.file.annotations;


import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * 为字段指定固定的键名，覆盖自动生成的命名规则。
 *
 * <p>此注解应用于字段。</p>
 *
 * @apiNote
 * 当默认命名策略（如 {@link api.linlang.file.annotations.NamingStyle}）无法满足要求时使用。
 * 例如：
 * <pre>
 * &#064;Key("custom-key")
 * public String myValue;
 * </pre>
 */
@Retention(RUNTIME)
@Target({FIELD})
public @interface Key {
    /**
     * 指定键名。
     */
    String value();
}