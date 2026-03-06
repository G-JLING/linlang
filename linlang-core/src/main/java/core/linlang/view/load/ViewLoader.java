package core.linlang.view.load;

import api.linlang.file.file.path.PathResolver;
import core.linlang.view.spec.ViewSpec;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ViewLoader {

    private final PathResolver paths;
    private final String uiRoot; // e.g. "gui"

    public ViewLoader(PathResolver paths, String uiRoot) {
        this.paths = paths;
        this.uiRoot = (uiRoot == null || uiRoot.isBlank()) ? "gui" : uiRoot;
    }

    public ViewSpec load(String viewId) {
        Path pYml = paths.root().resolve(uiRoot).resolve(viewId + ".yml");
        Path pYaml = paths.root().resolve(uiRoot).resolve(viewId + ".yaml");
        Path pJson = paths.root().resolve(uiRoot).resolve(viewId + ".json");

        try {
            if (Files.exists(pYml)) return ViewSpecParser.parseYaml(Files.readString(pYml));
            if (Files.exists(pYaml)) return ViewSpecParser.parseYaml(Files.readString(pYaml));
            if (Files.exists(pJson)) return ViewSpecParser.parseJson(Files.readString(pJson));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load view=" + viewId + ": " + e.getMessage(), e);
        }
        return null;
    }
}