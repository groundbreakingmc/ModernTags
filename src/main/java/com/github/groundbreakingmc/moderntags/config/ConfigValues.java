package com.github.groundbreakingmc.moderntags.config;

import com.github.groundbreakingmc.gikymessage.Text;
import com.github.groundbreakingmc.moderntags.config.model.TagEntry;
import com.github.groundbreakingmc.moderntags.config.model.TagGroup;
import com.github.groundbreakingmc.moderntags.config.model.LegacyTag;
import com.github.groundbreakingmc.moderntags.config.model.ModernTag;
import com.github.groundbreakingmc.moderntags.render.ModernRenderer;
import com.github.groundbreakingmc.moderntags.requirement.Condition;
import com.github.groundbreakingmc.moderntags.requirement.ConditionParser;
import com.github.groundbreakingmc.moderntags.text.TagTextResolver;
import com.github.groundbreakingmc.moderntags.text.TagTextResolver.NumericTemplate;
import com.github.groundbreakingmc.mylib.config.Config;
import com.github.groundbreakingmc.mylib.config.ConfigBackends;
import com.github.groundbreakingmc.mylib.config.ConfigFactory;
import com.google.common.collect.ImmutableList;
import com.github.retrooper.packetevents.PacketEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fully loaded configuration snapshot. Publish a new instance on reload; retain one
 * snapshot throughout each operation. Loading state is never exposed to readers.
 */
public record ConfigValues(
        boolean checkForUpdates,
        boolean autoDownloadUpdates,
        boolean useMinimessageColorizer,
        boolean hideTagWhenHasPassenger,
        int tickRate,
        boolean syncAnimationOnEachTarget,
        int conditionsRecheckRate,
        @NotNull NumericTemplate belowNameValue,
        @NotNull Component belowNameChar,
        @NotNull TagSelector selector,
        @NotNull Map<ModernTag, List<ModernRenderer.CompiledFrame>> modernFrames
) {

    /** Copies prepared frame lists, preserving definition identity and preventing external mutation. */
    public ConfigValues {
        final var frames = new IdentityHashMap<ModernTag, List<ModernRenderer.CompiledFrame>>();
        modernFrames.forEach((tag, compiled) -> frames.put(tag, List.copyOf(compiled)));
        modernFrames = Collections.unmodifiableMap(frames);
    }

    /** Loads and validates a complete snapshot; failure leaves the published configuration untouched. */
    public static @NotNull ConfigValues of(@NotNull File pluginFolder, @NotNull TagTextResolver textResolver) {
        final Config root;
        try {
            root = ConfigFactory.of(new File(pluginFolder, "config.yml")).backend(ConfigBackends.CONFIGURATE_YAML).load();
        } catch (Exception ex) {
            throw new RuntimeException("[ModernTags] Failed to load config.yml from \"" + pluginFolder + "\"", ex);
        }
        return new Loader(pluginFolder, textResolver, root.findBool("vault-mm-formatting")).load(root);
    }

    /** Returns this snapshot's immutable groups in selection order. */
    public @NotNull List<TagGroup> tags() {
        return this.selector.groups();
    }

    /** Returns shared prepared frames for a definition belonging to this snapshot. */
    public @NotNull List<ModernRenderer.CompiledFrame> modernFrames(@NotNull ModernTag tag) {
        final var frames = this.modernFrames.get(tag);
        if (frames == null) throw new IllegalArgumentException("Modern tag does not belong to this configuration");
        return frames;
    }

    private record Loader(File pluginFolder, TagTextResolver textResolver, boolean useMinimessageColorizer) {

        private ConfigValues load(Config root) {
            final boolean checkForUpdates = root.boolOr("updates.check", true);
            final boolean autoDownloadUpdates = root.boolOr("updates.auto-download", false);
            final boolean hideTagWhenHasPassenger = root.findBool("hide-tag-when-has-passenger");
            final int tickRate = root.findInt("tick-rate");
            if (tickRate <= 0) throw new IllegalArgumentException("tick-rate must be positive");
            final boolean syncAnimationOnEachTarget = root.boolOr("sync-animation-on-each-target", true);
            final int conditionsRecheckRate = root.intOr("conditions-recheck-rate", 20);
            if (conditionsRecheckRate != -1 && conditionsRecheckRate <= 0) {
                throw new IllegalArgumentException("conditions-recheck-rate must be -1 or a positive integer");
            }
            final var belowNameValue = this.textResolver.compileNumeric(
                    root.findStr("legacy-general.below-name-value"), this.useMinimessageColorizer);
            final var belowNameChar = Text.of(root.findStr("legacy-general.below-name-text")).render();
            final var modernFrames = new IdentityHashMap<ModernTag, List<ModernRenderer.CompiledFrame>>();
            final var selector = this.loadNameTags(root, modernFrames);
            return new ConfigValues(checkForUpdates, autoDownloadUpdates, this.useMinimessageColorizer,
                    hideTagWhenHasPassenger, tickRate, syncAnimationOnEachTarget, conditionsRecheckRate,
                    belowNameValue, belowNameChar, selector, modernFrames);
        }

        private TagSelector loadNameTags(Config root, Map<ModernTag, List<ModernRenderer.CompiledFrame>> modernFrames) {
            final Map<String, Config> configCache = new HashMap<>();
            final Map<String, ModernTag> modernCache = new HashMap<>();
            final Map<String, LegacyTag> legacyCache = new HashMap<>();
            final var groupSections = root.findSectionList("tags");

            final var groupsBuilder = ImmutableList.<TagGroup>builder();
            for (int i = 0; i < groupSections.size(); i++) {
                try {
                    groupsBuilder.add(this.parseGroup(
                            configCache, modernCache, legacyCache, groupSections.get(i), i));
                } catch (Exception ex) {
                    throw new RuntimeException(
                            "[ModernTags] Failed to parse tag group #" + i + " in config.yml (tags[" + i + "])", ex);
                }
            }

            final var selector = new TagSelector(groupsBuilder.build().stream()
                    .sorted(Comparator.comparingInt(TagGroup::priority).reversed())
                    .collect(ImmutableList.toImmutableList()));

            final var version = PacketEvents.getAPI().getServerManager().getVersion();
            for (final var tag : modernCache.values()) {
                modernFrames.put(tag, ModernRenderer.compileFrames(tag, version));
            }
            return selector;
        }

        private TagGroup parseGroup(Map<String, Config> configCache,
                                    Map<String, ModernTag> modernCache,
                                    Map<String, LegacyTag> legacyCache,
                                    Config section,
                                    int groupIdx) {
            final int priority = section.findInt("priority");
            final String context = "tags[" + groupIdx + "] (priority=" + priority + ")";

            final var condition = this.parseCondition(
                    section.strOr("owner-conditions", null),
                    context + ".owner-conditions"
            );

            final var entrySections = section.findSectionList("entries");
            final var entriesBuilder = ImmutableList.<TagEntry>builder();
            for (int i = 0; i < entrySections.size(); i++) {
                try {
                    entriesBuilder.add(this.parseEntry(
                            configCache, modernCache, legacyCache, entrySections.get(i), context, i));
                } catch (Exception ex) {
                    throw new RuntimeException(
                            "[ModernTags] Failed to parse entry #" + i + " in " + context, ex);
                }
            }

            final var entries = entriesBuilder.build().stream()
                    .sorted(Comparator.comparingInt(TagEntry::priority).reversed())
                    .collect(ImmutableList.toImmutableList());

            return new TagGroup(priority, condition, entries);
        }

        private TagEntry parseEntry(Map<String, Config> configCache,
                                    Map<String, ModernTag> modernCache,
                                    Map<String, LegacyTag> legacyCache,
                                    Config section,
                                    String groupContext,
                                    int entryIdx) {
            final int priority = section.findInt("priority");
            final String context = groupContext + ".entries[" + entryIdx + "] (priority=" + priority + ")";

            final var condition = this.parseCondition(
                    section.strOr("viewer-conditions", null),
                    context + ".viewer-conditions"
            );

            final String modernPath = section.findStr("modern");
            final String legacyPath = section.strOr("legacy", null);

            final var modern = modernCache.computeIfAbsent(modernPath,
                    path -> this.parseModernTag(this.loadNode(configCache, path, context), context + ".modern (" + path + ")"));

            final var legacy = legacyPath == null ? null : legacyCache.computeIfAbsent(legacyPath,
                    path -> this.parseLegacyTag(this.loadNode(configCache, path, context), context + ".legacy (" + path + ")"));

            return new TagEntry(priority, condition, modern, legacy);
        }

        private Config loadNode(Map<String, Config> configCache, String rawPath, String context) {
            final var pathParts = this.splitPath(rawPath, context);
            final var fileRoot = this.loadFile(configCache, pathParts[0], context);
            return this.resolveSection(fileRoot, pathParts[0], pathParts[1], context);
        }

        private ModernTag parseModernTag(Config node, String context) {
            final int frameRate = readRate(node, "frame-update-rate", context);
            final int placeholderRate = readRate(node, "placeholders-update-rate", context);
            final var sections = node.findSectionList("frames");
            final var frames = new ArrayList<ModernTag.Frame>(sections.size());
            for (int i = 0; i < sections.size(); i++) {
                final String frameContext = context + ".frames[" + i + "]";
                try {
                    frames.add(this.parseModernFrame(sections.get(i), frameContext));
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("[ModernTags] Invalid modern frame at " + frameContext, exception);
                }
            }
            if (frames.isEmpty()) throw new IllegalArgumentException(context + ".frames must contain at least one frame");
            return new ModernTag(frameRate, placeholderRate, frames);
        }

        private ModernTag.Frame parseModernFrame(Config node, String context) {
            final var text = this.textResolver.compile(node.findStr("text"), this.useMinimessageColorizer);
            final String alignment = node.strOr("alignment", "CENTER").strip().toUpperCase(Locale.ROOT);
            byte flags = switch (alignment) {
                case "CENTER" -> 0;
                case "LEFT" -> 0x08;
                case "RIGHT" -> 0x10;
                default -> throw new IllegalArgumentException(context + ".alignment must be LEFT, CENTER or RIGHT");
            };
            if (node.boolOr("shadowed", true)) flags |= 0x01;
            if (node.boolOr("see-through", false)) flags |= 0x02;
            if (node.boolOr("default-background", false)) flags |= 0x04;

            final String brightness = node.strOr("brightness", "block-15").strip();
            final var parts = brightness.split("-", 2);
            if (parts.length != 2) throw new IllegalArgumentException(context + ".brightness must be block-<0-15> or sky-<0-15>");
            final int strength = Integer.parseInt(parts[1]);
            if (strength < 0 || strength > 15) throw new IllegalArgumentException(context + ".brightness strength must be between 0 and 15");
            final int encodedBrightness = switch (parts[0].toLowerCase(Locale.ROOT)) {
                case "block" -> strength << 4;
                case "sky" -> strength << 20;
                default -> throw new IllegalArgumentException(context + ".brightness must be block-<0-15> or sky-<0-15>");
            };

            final String background = node.strOr("background-color", "#00000000").strip();
            final String hex = background.startsWith("#") ? background.substring(1) : background;
            if (!hex.matches("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")) {
                throw new IllegalArgumentException(context + ".background-color must be #RRGGBB or #AARRGGBB");
            }
            final int argb = (int) Long.parseLong(hex, 16) | (hex.length() == 6 ? 0xFF000000 : 0);

            return new ModernTag.Frame(text,
                    readFloat(node, "x-offset", 0.0, context),
                    readFloat(node, "y-offset", 0.15, context),
                    readFloat(node, "z-offset", 0.0, context),
                    readFloat(node, "scale", 1.0, context),
                    node.boolOr("vertical-billboard", false), encodedBrightness,
                    readFloat(node, "view-range", 1.0, context),
                    readFloat(node, "shadow-radius", 0.0, context),
                    readFloat(node, "shadow-strength", 1.0, context),
                    node.intOr("line-width", 200), argb,
                    readOpacity(node, "text-opacity", -1, context),
                    readOpacity(node, "sneak-opacity", 60, context), flags);
        }

        private LegacyTag parseLegacyTag(Config node, String context) {
            final int frameRate = readRate(node, "frame-update-rate", context);
            final int placeholderRate = readRate(node, "placeholders-update-rate", context);
            final var ignoredColors = new HashSet<NamedTextColor>();
            for (final var raw : node.strListOr("ignored-colors", List.of("white"))) {
                ignoredColors.add(readColor(raw, context + ".ignored-colors"));
            }
            final var sections = node.findSectionList("frames");
            final var frames = new ArrayList<LegacyTag.Frame>(sections.size());
            for (int i = 0; i < sections.size(); i++) {
                final String frameContext = context + ".frames[" + i + "]";
                try {
                    final var frame = sections.get(i);
                    frames.add(new LegacyTag.Frame(this.textResolver.compile(frame.findStr("prefix"), this.useMinimessageColorizer),
                            readColor(frame.findStr("name-color"), frameContext + ".name-color"),
                            this.textResolver.compile(frame.findStr("suffix"), this.useMinimessageColorizer)));
                } catch (RuntimeException exception) {
                    throw new IllegalArgumentException("[ModernTags] Invalid legacy frame at " + frameContext, exception);
                }
            }
            if (frames.isEmpty()) throw new IllegalArgumentException(context + ".frames must contain at least one frame");
            return new LegacyTag(frameRate, placeholderRate,
                    node.boolOr("preserve-player-name-color", false), ignoredColors, frames);
        }

        private static int readRate(Config node, String key, String context) {
            final int rate = node.findInt(key);
            if (rate != -1 && rate <= 0) throw new IllegalArgumentException(context + "." + key + " must be -1 or positive");
            return rate;
        }

        private static float readFloat(Config node, String key, double fallback, String context) {
            final float value = (float) node.doubleOr(key, fallback);
            if (!Float.isFinite(value)) throw new IllegalArgumentException(context + "." + key + " must be a finite float");
            return value;
        }

        private static byte readOpacity(Config node, String key, int fallback, String context) {
            final int value = node.intOr(key, fallback);
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                throw new IllegalArgumentException(context + "." + key + " must be between -128 and 127");
            }
            return (byte) value;
        }

        private static NamedTextColor readColor(String raw, String context) {
            final var color = NamedTextColor.NAMES.value(raw.strip().toLowerCase(Locale.ROOT));
            if (color == null) throw new IllegalArgumentException(context + ": unknown named color \"" + raw + "\"");
            return color;
        }

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
                        ConfigFactory.of(new File(this.pluginFolder, path)).backend(ConfigBackends.CONFIGURATE_YAML).load()
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

            final var sectionConfig = fileRoot.sectionOr(section, null);
            if (sectionConfig == null) {
                throw new IllegalArgumentException(
                        "[ModernTags] Section \"" + section + "\" not found in file \""
                                + filePath + "\" (referenced by " + context + ")");
            }
            return sectionConfig;
        }
    }
}
