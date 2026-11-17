# Text and placeholders

The `text`, legacy `prefix` and `suffix`, and `legacy-general.below-name-text` fields use
GikyMessage. Start with its
[quick start](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/quick-start.md)
or [cheat sheet](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/cheatsheet.md).

| To configure | Read in the GikyMessage wiki |
|---|---|
| Colors, styles and resets | [Colors and styles](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/colors.md) |
| A gradient or moving colors | [Gradients](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/gradients.md) |
| Show part of the text conditionally | [Conditions](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/conditions.md) |
| Placeholder values and their formatting | [Placeholders](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/placeholders.md) |
| Literal brackets and YAML quotes | [Escaping and quotes](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/escapes.md) |

GikyMessage conditions apply inside the text. The `owner-conditions` and `viewer-conditions`
settings use ModernTags' own [tag selection syntax](conditions.md).

## Text formatting

[HEX colors and text shadows](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/colors.md)
use six digits: `&#ff8800` and `$#ff8800`. A text shadow's alpha follows a colon: `$#ff8800:80`.
Colors and shadows can also be written as `#RRGGBB` and `$RRGGBB`.

To display formatting symbols literally,
[escape them with a backslash](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/escapes.md):

```yaml
text: '\#123456 \$100000 R\&D' # Displays: #123456 $100000 R&D
```

The separate TextDisplay `background-color` setting accepts `#RRGGBB` or `#AARRGGBB`.

## Placeholders

- `{owner:...}`: the player the tag hangs over
- `{viewer:...}`: the player who sees the tag
- `{rel:...}`: the relation between the two ([explained below](#relational-placeholders))

```yaml
text: "{owner:prefix}{owner:name}"
```

ModernTags has five placeholders of its own: `name`, `display_name`, `health`, and `prefix`
and `suffix` from Vault or VaultSlim. Every other placeholder comes from PlaceholderAPI: write
it without `%` and with `owner:` or `viewer:` in front.

| PlaceholderAPI | In a tag |
|---|---|
| `%player_level%` | `{owner:player_level}` or `{viewer:player_level}` |
| `%rel_...%` | `{rel:...}` |

ModernTags parses legacy colors in PlaceholderAPI values: `&a`, `§a` and `&#RRGGBB`.
Vault `prefix` and `suffix` values use MiniMessage when `vault-mm-formatting: true`, and legacy
formatting when it is `false`. This setting only affects those values; tag templates always use
GikyMessage. A value's formatting ends with the placeholder, so the text after it keeps the
template's color and style. Placeholder values are not parsed as GikyMessage segments or actions.

## Relational placeholders

Some plugins have [relational placeholders](https://github.com/PlaceholderAPI/PlaceholderAPI/wiki/Placeholders#factions-relation-placeholders):
they take two players at once, for example to show above a player whether they are your
teammate.

Take a clan plugin. With an `owner:` placeholder you can only ask it "is Steve in clan A?",
with a `viewer:` placeholder "is Alex in clan A?". A `rel:` placeholder asks "are Steve and
Alex in the same clan?". `%rel_factionsuuid_relation%` is written as
`{rel:factionsuuid_relation}`:

```yaml
text: "{rel:factionsuuid_relation}{owner:name}"
```

## Several lines

```yaml
text: |-
  {owner:prefix}{owner:name}
  &c❤ {owner:health}
```

## Brackets

In GikyMessage, square brackets start a segment, so `[VIP]` loses its brackets. To display them literally, [escape them with a backslash](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/escapes.md). Use single quotes, since [YAML does not recognize `\[` as a valid escape sequence](https://yaml.org/spec/1.2.2/#57-escaped-characters) inside double quotes.

```yaml
text: '&6\[VIP\] &f{owner:name}'
```