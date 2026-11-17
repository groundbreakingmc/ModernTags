# Примеры

## Тег для группы LuckPerms

LuckPerms даёт каждому участнику группы право `group.<название>`.

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

## Дополнительная информация для персонала

Персонал видит у всех здоровье и пинг, остальные — обычный тег. У записи для персонала приоритет
выше, поэтому она проверяется первой.

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

Персонал на клиентах старше 1.19.4 получает обычный тег: у записи для персонала нет `legacy`.

## Бегущий градиент

Каждый кадр сдвигает градиент на четверть, и цвета бегут по нику.

```yaml
frame-update-rate: 5
placeholders-update-rate: 20
frames:
  - text: '[{owner:name}](gradient:#ff5555-#5555ff, phase:0)'
  - text: '[{owner:name}](gradient:#ff5555-#5555ff, phase:0.25)'
  - text: '[{owner:name}](gradient:#ff5555-#5555ff, phase:0.5)'
  - text: '[{owner:name}](gradient:#ff5555-#5555ff, phase:0.75)'
```

## Значок с 30 уровня

```yaml
# config.yml
tags:
  - priority: 5
    owner-conditions: "%player_level% >= 30"
    entries:
      - priority: 0
        modern: tags/level.yml:<root>
  # ... остальные группы
```

```yaml
# tags/level.yml
frame-update-rate: -1
placeholders-update-rate: 40
frames:
  - text: '&6★{owner:player_level} &f{owner:name}'
```

## Все теги в одном месте

Ссылка указывает на любую секцию любого файла в `plugins/ModernTags/`, поэтому все теги можно
держать в одном файле или даже прямо в `config.yml`:

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
