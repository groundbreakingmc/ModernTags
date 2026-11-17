# Текст и плейсхолдеры

Поля `text`, `prefix` и `suffix` legacy-тегов, а также `legacy-general.below-name-text`
используют GikyMessage. Начните с его
[быстрого старта](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/ru/quick-start.md)
или [шпаргалки](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/ru/cheatsheet.md).

| Что настроить | Статья в wiki GikyMessage |
|---|---|
| Цвета, стили и сброс оформления | [Цвета и стили](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/ru/colors.md) |
| Градиент или бегущие цвета | [Градиенты](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/ru/gradients.md) |
| Показ части текста по условию | [Условия](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/ru/conditions.md) |
| Значения плейсхолдеров и их оформление | [Плейсхолдеры](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/ru/placeholders.md) |
| Буквальные скобки и кавычки YAML | [Экранирование и кавычки](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/ru/escapes.md) |

Условия GikyMessage работают внутри текста. Настройки `owner-conditions` и `viewer-conditions`
используют собственный [синтаксис выбора тега](conditions.md) ModernTags.

## Форматирование текста

[HEX-цвета и тени текста](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/ru/colors.md)
записываются шестью цифрами: `&#ff8800` и `$#ff8800`. Альфа тени указывается после двоеточия:
`$#ff8800:80`. Цвета и тени можно записывать и как `#RRGGBB` и `$RRGGBB`.

Чтобы показать символы форматирования буквально,
[экранируйте их обратной косой чертой](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/ru/escapes.md):

```yaml
text: '\#123456 \$100000 R\&D' # Отобразится: #123456 $100000 R&D
```

Отдельная настройка TextDisplay `background-color` принимает `#RRGGBB` или `#AARRGGBB`.

## Плейсхолдеры

- `{owner:...}`: игрок, над которым отображается тег
- `{viewer:...}`: игрок, который видит тег
- `{rel:...}`: отношение между двумя игроками ([подробнее ниже](#relational-placeholders))

```yaml
text: "{owner:prefix}{owner:name}"
```

У ModernTags пять своих плейсхолдеров: `name`, `display_name`, `health`, а также `prefix` и
`suffix` из Vault или VaultSlim. Все остальные берутся из PlaceholderAPI: пишите их без `%` и
с `owner:` или `viewer:` спереди.

| PlaceholderAPI | В теге |
|---|---|
| `%player_level%` | `{owner:player_level}` или `{viewer:player_level}` |
| `%rel_...%` | `{rel:...}` |

ModernTags разбирает legacy-цвета в значениях PlaceholderAPI: `&a`, `§a` и `&#RRGGBB`.
Значения `prefix` и `suffix` из Vault используют MiniMessage при `vault-mm-formatting: true`
и legacy-форматирование при `false`. Настройка влияет только на эти значения; сами шаблоны тегов
всегда используют GikyMessage. Оформление значения заканчивается вместе с плейсхолдером, поэтому
текст после него сохраняет цвет и стиль шаблона. Значения плейсхолдеров не разбираются как сегменты
или действия GikyMessage.

## Относительные плейсхолдеры

У некоторых плагинов есть [относительные плейсхолдеры](https://github.com/PlaceholderAPI/PlaceholderAPI/wiki/Placeholders#factions-relation-placeholders):
они берут сразу двух игроков, например чтобы показать над игроком, тиммейт он вам или нет.

Возьмём плагин на кланы. Через плейсхолдер `owner:` у него можно спросить только «Steve в клане
A?», через `viewer:` — «Alex в клане A?». Плейсхолдер `rel:` спрашивает «Steve и Alex в одном
клане?». `%rel_factionsuuid_relation%` правильно записывать как `{rel:factionsuuid_relation}`:

```yaml
text: "{rel:factionsuuid_relation}{owner:name}"
```

## Несколько строк

```yaml
text: |-
  {owner:prefix}{owner:name}
  &c❤ {owner:health}
```

## Скобки

В GikyMessage квадратные скобки начинают сегмент, поэтому `[VIP]` отображается без скобок. Чтобы показать их буквально, [экранируйте их обратным слешем](https://github.com/groundbreakingmc/GikyMessage/blob/main/wiki/ru/escapes.md). Используйте одинарные кавычки, поскольку [YAML не допускает `\[` как корректную escape-последовательность](https://yaml.org/spec/1.2.2/#57-escaped-characters) внутри двойных кавычек.

```yaml
text: '&6\[VIP\] &f{owner:name}'
```
