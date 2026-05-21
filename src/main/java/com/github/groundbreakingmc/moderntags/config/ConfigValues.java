package com.github.groundbreakingmc.moderntags.config;

import com.github.groundbreakingmc.gikymessage.Text;
import com.github.groundbreakingmc.moderntags.config.model.TagEntry;
import com.github.groundbreakingmc.moderntags.config.model.TagGroup;
import com.github.groundbreakingmc.moderntags.renderer.LegacyRenderer;
import com.github.groundbreakingmc.moderntags.renderer.ModernRenderer;
import com.github.groundbreakingmc.moderntags.requirement.Condition;
import com.github.groundbreakingmc.moderntags.requirement.ConditionParser;
import com.github.groundbreakingmc.moderntags.text.TagTextResolver;
import com.github.groundbreakingmc.moderntags.util.NumberUtils;
import com.github.groundbreakingmc.mylib.config.Config;
import com.github.groundbreakingmc.mylib.config.ConfigBackends;
import com.github.groundbreakingmc.mylib.config.ConfigFactory;
import com.google.common.collect.ImmutableList;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntBiFunction;

/**
 * Loads and exposes all plugin configuration values.
 *
 * <p>Call {@link #setup()} to (re)load from disk. After setup, all getters are safe
 * to read from any thread. Renderer factories are registered once at startup via
 * {@link #registerModernFactory} and {@link #registerLegacyFactory}.
 */
public final class ConfigValues {

    private final File pluginFolder;
    private final TagTextResolver textResolver;

    private Function<Config, ModernRenderer> modernFactory;
    private Function<Config, LegacyRenderer> legacyFactory;

    private boolean useMinimessageColorizer;
    private boolean hideTagWhenHasPassenger;
    private int tickRate;
    private ToIntBiFunction<Player, Player> belowNameValueParser;
    private Component belowNameChar;
    private List<TagGroup> tags;

    public ConfigValues(@NotNull File pluginFolder, @NotNull TagTextResolver textResolver) {
        this.pluginFolder = pluginFolder;
        this.textResolver = textResolver;
    }

    public void registerModernFactory(@NotNull Function<Config, ModernRenderer> factory) {
        this.modernFactory = factory;
    }

    public void registerLegacyFactory(@NotNull Function<Config, LegacyRenderer> factory) {
        this.legacyFactory = factory;
    }

    /**
     * Loads (or reloads) all settings and tag groups from disk.
     */
    public void setup() {
        final Config root;
        try {
            root = ConfigFactory.of(this.pluginFolder).backend(ConfigBackends.CONFIGURATE_YAML).load();
        } catch (Exception ex) {
            throw new RuntimeException("[ModernTags] Failed to load config.yml from \"" + this.pluginFolder + "\"", ex);
        }

        this.loadSettings(root);
        this.loadNameTags(root);
    }

    public boolean useMinimessageColorizer() {
        return this.useMinimessageColorizer;
    }

    public boolean hideTagWhenHasPassenger() {
        return this.hideTagWhenHasPassenger;
    }

    public int tickRate() {
        return this.tickRate;
    }

    public ToIntBiFunction<Player, Player> belowNameValueParser() {
        return this.belowNameValueParser;
    }

    public Component belowNameChar() {
        return this.belowNameChar;
    }

    public List<TagGroup> tags() {
        return this.tags;
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private void loadSettings(Config root) {
        this.useMinimessageColorizer = root.findBool("vault-mm-formatting");
        this.hideTagWhenHasPassenger = root.findBool("hide-tag-when-has-passenger");
        this.tickRate = root.findInt("tick-rate");
        final String belowNameValue = root.findStr("legacy-general.below-name-value");
        this.belowNameValueParser = belowNameValue.equals("owner:health")
                ? (target, viewer) -> NumberUtils.healthToInt(target.getHealth())
                : this.textResolver.intFromPlaceholder(belowNameValue);
        this.belowNameChar = Text.of(root.findStr("legacy-general.below-name-text")).render();
    }

    // ── Tag groups ────────────────────────────────────────────────────────────

    private void loadNameTags(Config root) {
        final Map<String, Config> configCache = new HashMap<>();
        final List<Config> groupSections = root.findSectionList("tags");

        final ImmutableList.Builder<TagGroup> builder = ImmutableList.builder();
        for (int i = 0; i < groupSections.size(); i++) {
            try {
                builder.add(this.parseGroup(configCache, groupSections.get(i), i));
            } catch (Exception ex) {
                throw new RuntimeException(
                        "[ModernTags] Failed to parse tag group #" + i + " in config.yml (tags[" + i + "])", ex);
            }
        }

        this.tags = builder.build().stream()
                .sorted(Comparator.comparing(TagGroup::priority).reversed())
                .collect(ImmutableList.toImmutableList());
    }

    private TagGroup parseGroup(Map<String, Config> configCache, Config section, int groupIndex) {
        final int priority = section.findInt("priority");
        final String context = "tags[" + groupIndex + "] (priority=" + priority + ")";

        final Condition condition = this.parseCondition(
                section.strOr("owner-conditions", null),
                context + ".owner-conditions"
        );

        final List<Config> entrySections = section.findSectionList("entries");
        final ImmutableList.Builder<TagEntry> builder = ImmutableList.builder();
        for (int i = 0; i < entrySections.size(); i++) {
            try {
                builder.add(this.parseEntry(configCache, entrySections.get(i), context, i));
            } catch (Exception ex) {
                throw new RuntimeException(
                        "[ModernTags] Failed to parse entry #" + i + " in " + context, ex);
            }
        }

        final List<TagEntry> entries = builder.build().stream()
                .sorted(Comparator.comparing(TagEntry::priority).reversed())
                .collect(ImmutableList.toImmutableList());

        return new TagGroup(priority, condition, entries);
    }

    private TagEntry parseEntry(Map<String, Config> configCache,
                                Config section,
                                String groupContext,
                                int entryIndex) {
        final int priority = section.findInt("priority");
        final String context = groupContext + ".entries[" + entryIndex + "] (priority=" + priority + ")";

        final Condition condition = this.parseCondition(
                section.strOr("viewer-conditions", null),
                context + ".viewer-conditions"
        );

        final Config modernNode = loadNode(configCache, section.findStr("modern"), context);
        final Config legacyNode = loadNode(configCache, section.findStr("legacy"), context);

        final ModernRenderer modern = this.buildModernRenderer(modernNode, context);
        final LegacyRenderer legacy = this.buildLegacyRenderer(legacyNode, context);

        return new TagEntry(priority, condition, modern, legacy);
    }

    private Config loadNode(Map<String, Config> configCache, String rawPath, String context) {
        final String[] parts = this.splitPath(rawPath, context);
        final Config fileRoot = this.loadFile(configCache, parts[0], context);
        return this.resolveSection(fileRoot, parts[0], parts[1], context);
    }

    // ── Renderer builders ─────────────────────────────────────────────────────

    private ModernRenderer buildModernRenderer(Config node, String context) {
        try {
            return this.modernFactory.apply(node);
        } catch (Exception ex) {
            throw new RuntimeException("[ModernTags] Failed to build ModernRenderer at " + context, ex);
        }
    }

    private LegacyRenderer buildLegacyRenderer(Config node, String context) {
        try {
            return this.legacyFactory.apply(node);
        } catch (Exception ex) {
            throw new RuntimeException("[ModernTags] Failed to build LegacyRenderer at " + context, ex);
        }
    }

    // ── Path helpers ──────────────────────────────────────────────────────────

    @Nullable
    private Condition parseCondition(@Nullable String raw, String context) {
        if (raw == null) return null;
        try {
            return ConditionParser.parse(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "[ModernTags] Failed to parse condition at " + context + ": \"" + raw + "\"", ex);
        }
    }

    private String[] splitPath(String rawPath, String context) {
        final int colonIdx = rawPath.indexOf(':');
        if (colonIdx < 1 || colonIdx == rawPath.length() - 1) {
            throw new IllegalArgumentException(
                    "[ModernTags] Invalid path format at " + context
                            + ": expected \"<file>:<section|<root>>\", got \"" + rawPath + "\"");
        }
        return new String[]{rawPath.substring(0, colonIdx), rawPath.substring(colonIdx + 1)};
    }

    private Config loadFile(Map<String, Config> cache, String relativePath, String context) {
        try {
            return cache.computeIfAbsent(relativePath, path ->
                    ConfigFactory.of(this.pluginFolder).file(path).backend(ConfigBackends.CONFIGURATE_YAML).load()
            );
        } catch (Exception ex) {
            throw new RuntimeException(
                    "[ModernTags] Failed to load file \"" + relativePath + "\" referenced by " + context, ex);
        }
    }

    private Config resolveSection(Config fileRoot, String filePath, String section, String context) {
        if (fileRoot.keys(false).isEmpty()) {
            throw new IllegalStateException(
                    "[ModernTags] File \"" + filePath + "\" loaded empty — possible YAML parse error.");
        }
        if (section.equals("<root>")) return fileRoot;

        final Config sectionConfig = fileRoot.sectionOr(section, null);
        if (sectionConfig == null) {
            throw new IllegalArgumentException(
                    "[ModernTags] Section \"" + section + "\" not found in file \""
                            + filePath + "\" (referenced by " + context + ")");
        }
        return sectionConfig;
    }
}
