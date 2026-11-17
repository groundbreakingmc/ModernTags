# Examples

## A tag for a LuckPerms group

LuckPerms gives every member of a group the permission `group.<name>`.

```yaml
# config.yml
tags:
  - priority: 10
    owner-conditions: "hasPermission('group.admin')"
    entries:
      - priority: 0
        modern: tags/ranks.yml:admin
        legacy: tags/ranks.yml:admin-legacy

  - priority: 0
    entries:
      - priority: 0
        modern: tags/default.yml:<root>
        legacy: tags/default-legacy.yml:<root>
```

```yaml
# tags/ranks.yml
admin:
  frame-update-rate: -1
  placeholders-update-rate: 20
  frames:
    - text: '&c\[Admin\] &f{owner:name}'

admin-legacy:
  frame-update-rate: -1
  placeholders-update-rate: 20
  frames:
    - prefix: '&c\[Admin\] '
      name-color: "white"
      suffix: ""
```

## Extra information for staff

Staff see everyone's health and ping, other players see the normal tag. The staff entry has
the higher priority, so it is tried first.

```yaml
# config.yml
tags:
  - priority: 0
    entries:
      - priority: 1
        viewer-conditions: "hasPermission('server.staff')"
        modern: tags/staff.yml:<root>
      - priority: 0
        modern: tags/default.yml:<root>
        legacy: tags/default-legacy.yml:<root>
```

```yaml
# tags/staff.yml
frame-update-rate: -1
placeholders-update-rate: 10
frames:
  - text: |-
      {owner:prefix}{owner:name}
      &c❤ {owner:health} &7{owner:player_ping}ms
```

Staff on clients older than 1.19.4 get the normal tag, since the staff entry has no `legacy`.

## A moving gradient

Each frame shifts the gradient by a quarter, so the colors run along the name.

```yaml
frame-update-rate: 5
placeholders-update-rate: 20
frames:
  - text: '[{owner:name}](gradient:#ff5555-#5555ff, phase:0)'
  - text: '[{owner:name}](gradient:#ff5555-#5555ff, phase:0.25)'
  - text: '[{owner:name}](gradient:#ff5555-#5555ff, phase:0.5)'
  - text: '[{owner:name}](gradient:#ff5555-#5555ff, phase:0.75)'
```

## A badge from level 30

```yaml
# config.yml
tags:
  - priority: 5
    owner-conditions: "%player_level% >= 30"
    entries:
      - priority: 0
        modern: tags/level.yml:<root>
  # ... other groups
```

```yaml
# tags/level.yml
frame-update-rate: -1
placeholders-update-rate: 40
frames:
  - text: '&6★{owner:player_level} &f{owner:name}'
```

## All tags in one place

A reference points to any section of any file in `plugins/ModernTags/`, so all tags can live
in one file, or even in `config.yml` itself:

```yaml
# config.yml
tags:
  - priority: 0
    entries:
      - priority: 0
        modern: config.yml:my-tags.default
        legacy: config.yml:my-tags.default-legacy

my-tags:
  default:
    frame-update-rate: -1
    placeholders-update-rate: 10
    frames:
      - text: "{owner:name}"
  default-legacy:
    frame-update-rate: -1
    placeholders-update-rate: 10
    frames:
      - prefix: ""
        name-color: "white"
        suffix: ""
```
