package core.linlang.database.impl;

/**
 * 表示数据库实体声明无法映射为有效的数据表结构。
 */
final class DataMappingException extends IllegalArgumentException {

    DataMappingException(String message) {
        super(message);
    }

    DataMappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
