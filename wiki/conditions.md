# Choosing a tag

## Groups and entries

```yaml
tags:
  - priority: 1
    owner-conditions: "hasPermission('group.vip')"
    entries:
      - priority: 0
        modern: tags/vip.yml:modern
        legacy: tags/vip.yml:legacy

  - priority: 0
    entries:
      - priority: 0
        modern: tags/default.yml:<root>
        legacy: tags/default-legacy.yml:<root>
```

For every pair of owner and viewer, ModernTags goes through the groups from the highest
`priority` down and takes the first entry that fits:

- the group's `owner-conditions` must pass for the owner;
- the entry's `viewer-conditions` must pass for the viewer;
- a viewer older than 1.19.4 needs an entry with `legacy`.

Here a VIP gets the VIP tag and everyone else the default one. If no entry fits, the player
has no tag for that viewer.

`modern: tags/vip.yml:modern` means the section `modern` of `plugins/ModernTags/tags/vip.yml`;
`<root>` takes the whole file.

## Conditions

```yaml
owner-conditions: "hasPermission('group.vip') && %player_level% >= 10"
viewer-conditions: "{protocol_version} >= 767"
```

You can use:

- `hasPermission('permission')`
- PlaceholderAPI placeholders in `%`, for the player being checked
- `{protocol_version}`: the client's
  [protocol number](https://minecraft.wiki/w/Protocol_version), for example 767 is 1.21
- `&&` (and), `||` (or), `!` (not) and parentheses
- `==`, `!=`, `>`, `>=`, `<`, `<=`

Text goes in quotes, numbers without them:

```text
%luckperms_primary_group_name% == 'vip'
%player_level% >= 10
%player_level% == 10
```

A placeholder that answers yes or no can be used alone, for example
`%player_is_flying%` or `!%player_is_flying%`.

Conditions are checked again every `conditions-recheck-rate` ticks, so a new permission
changes the tag within that time.
