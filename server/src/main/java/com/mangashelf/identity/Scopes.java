package com.mangashelf.identity;

/** 首版 scope（DESIGN 17.1）；以逗号串存储在 api_tokens.scopes。 */
public final class Scopes {

    private Scopes() {
    }

    public static final String CATALOG_READ = "catalog:read";
    public static final String MATERIALS_READ = "materials:read";
    public static final String SEARCH_READ = "search:read";
    public static final String ANIMATIONS_WRITE = "animations:write";
    public static final String ANIMATIONS_PUBLISH = "animations:publish";

    public static final java.util.Set<String> ALL = java.util.Set.of(
            CATALOG_READ, MATERIALS_READ, SEARCH_READ, ANIMATIONS_WRITE, ANIMATIONS_PUBLISH);
}
