package audit.linlang.impl;

import api.linlang.file.file.ConfigService;
import api.linlang.file.file.path.PathResolver;
import audit.linlang.audit.AuditConfig;
import audit.linlang.audit.Auditor;
import audit.linlang.audit.Logger;

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