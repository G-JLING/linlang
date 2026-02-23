package core.linlang.interact.impl;

import api.linlang.interact.context.GuiContext;
import api.linlang.interact.model.GuiRow;
import api.linlang.interact.session.GuiSession;

import java.util.Map;

final class SimpleContext implements GuiContext {

    private final InteractCoreImpl core;
    private final GuiSession session;
    private final String areaId;
    private final String uid;
    private final GuiRow row;
    private final Map<String, Object> args;

    SimpleContext(InteractCoreImpl core, GuiSession session, String areaId, String uid, GuiRow row, Map<String, Object> args) {
        this.core = core;
        this.session = session;
        this.areaId = areaId == null ? "" : areaId;
        this.uid = uid == null ? "" : uid;
        this.row = row;
        this.args = args == null ? Map.of() : args;
    }

    @Override public GuiSession session() { return session; }
    @Override public String uid() { return uid; }
    @Override public String areaId() { return areaId; }
    @Override public GuiRow row() { return row; }
    @Override public Map<String, Object> args() { return args; }

    @Override public void refresh() { core.refreshView(session.viewer()); }

    @Override public void refreshArea(String areaId) { core.refreshArea(session.viewer(), areaId); }

    @Override public void close() { core.close(session.viewer()); }
}