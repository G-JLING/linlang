package io.linlang.filesystem.annotations;


import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/* 使用指定键覆盖字段名自动生成的键 */
@Retention(RUNTIME) @Target({FIELD})
public @interface Key { String value(); }

