package audit.linlang.impl;

import api.linlang.file.path.PathResolver;
import audit.linlang.audit.AuditConfig;
import audit.linlang.audit.Auditor;
import audit.linlang.audit.Logger;
import api.linlang.file.service.ConfigService;

// linlang-called/src/main/java/io/linlang/called/AuditImpl.java
public final class AuditImpl implements Auditor, Logger {
    public AuditImpl(PathResolver paths, ConfigService cfgs){
        AuditConfig cfg = cfgs.bind(AuditConfig.class);
    }

    @Override
    public void emit(String event, Object... kv) {

    }

    @Override
    public void debug(String msg, Object... kv) {

    }

    @Override
    public void info(String msg, Object... kv) {

    }

    @Override
    public void warn(String msg, Object... kv) {

    }

    @Override
    public void error(String msg, Throwable t, Object... kv) {

    }
}