# Performance

The cost depends on what the tags show. Nothing makes the same tags cheaper, but a few
choices change how much work they cause.

## Viewer placeholders

Take 50 players who all see each other, for example at spawn or at an event.

- `"{owner:name} &c❤ {owner:health}"` is the same for every viewer, so it is resolved once
  per owner: 50 times.
- `"{owner:name} &7ping {viewer:player_ping}"` differs per viewer, so it is resolved for every
  pair: 50 × 49 = 2450 times, almost 50².

One `{viewer:...}` or `{rel:...}` is enough to switch a tag to the second case. If only some
viewers need such a tag, give it to them in a separate entry, as in
[the staff example](examples.md#extra-information-for-staff).

## Update rates

- `placeholders-update-rate`: a longer interval for expensive PlaceholderAPI placeholders,
  `-1` for text that never changes. Only a text that changed is sent to the viewers.
- `frame-update-rate`: every frame change is sent to every viewer, so a slower animation
  means less traffic.
- `conditions-recheck-rate`: every recheck evaluates the conditions of all online players.
  `hasPermission` is cheap, a PlaceholderAPI placeholder costs what its expansion costs.

## PlaceholderAPI off the main thread

Placeholders and conditions are resolved outside the main server thread. Expansions that only
read data work fine there. Keep out of tags any expansion that changes the world or keeps
state without synchronisation.

Though if you come across such placeholders, better remove that plugin altogether:
placeholders are meant to read and compare data, never to change it.
