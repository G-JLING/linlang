package core.linlang.view.render;

public record RenderModel(
        String title,
        ItemModel[] items,
        ClickRoute[] routes
) {
    public int size() { return items == null ? 0 : items.length; }
}
