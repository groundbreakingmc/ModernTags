# Выбор тега

## Группы и записи

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

Для каждой пары владельца и зрителя ModernTags идёт по группам от наибольшего `priority` вниз и
берёт первую подходящую запись:

- `owner-conditions` группы должны выполняться для владельца;
- `viewer-conditions` записи должны выполняться для зрителя;
- зрителю на клиенте старше 1.19.4 нужна запись с `legacy`.

Здесь VIP получает тег VIP, а все остальные — обычный. Если не подошла ни одна запись, у игрока
для этого зрителя тега нет.

`modern: tags/vip.yml:modern` — это секция `modern` файла `plugins/ModernTags/tags/vip.yml`;
`<root>` берёт весь файл.

## Условия

```yaml
owner-conditions: "hasPermission('group.vip') && %player_level% >= 10"
viewer-conditions: "{protocol_version} >= 767"
```

Можно использовать:

- `hasPermission('право')`
- плейсхолдеры PlaceholderAPI в `%`, для проверяемого игрока
- `{protocol_version}`: [номер протокола](https://minecraft.wiki/w/Protocol_version) клиента,
  например 767 — это 1.21
- `&&` (и), `||` (или), `!` (не) и скобки
- `==`, `!=`, `>`, `>=`, `<`, `<=`

Текст пишется в кавычках, числа — без:

```text
%luckperms_primary_group_name% == 'vip'
%player_level% >= 10
%player_level% == 10
```

Плейсхолдер, который отвечает «да» или «нет», можно использовать сам по себе, например
`%player_is_flying%` или `!%player_is_flying%`.

Условия перепроверяются каждые `conditions-recheck-rate` тиков, так что новое право меняет тег
за это время.
