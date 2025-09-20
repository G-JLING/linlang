package io.linlang.filesystem.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/* 键的注释，生成在键之上一行 */
@Retention(RUNTIME) @Target({TYPE, FIELD})
public @interface Comment { String[] value(); }
