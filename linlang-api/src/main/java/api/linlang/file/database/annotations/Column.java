package api.linlang.file.database.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Retention(RUNTIME) @Target(FIELD)
public @interface Column {
    String name() default "";
    int length() default 0; boolean nullable() default true;
    String defaultValue() default "";
}
