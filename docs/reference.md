# Reference

Every value and function a script can use.

## Position

| Name | Is |
| --- | --- |
| `x`, `y`, `z` | Your exact position, to 2 decimals |
| `block` | Your block position, with `.x` `.y` `.z` |
| `chunk` | Your chunk, with `.x` `.z` |
| `yaw`, `pitch` | Where you are looking |
| `facing` | The direction you face out of 8, with `.name` (North East), `.short` (NE), `.axis` (+X -Z) and `.bearing` (degrees) |
| `cardinal` | The same as `facing`, but only North, East, South or West |
| `dimension` | `overworld`, `the_nether` or `the_end` |
| `biome` | The biome you're in |
| `speed` | Your speed in blocks per second, smoothed like the Speed element |

## Player

| Name | Is |
| --- | --- |
| `name` | Your username |
| `head` | Your player head, as an icon |
| `health`, `max_health`, `absorption` | Health in half hearts |
| `food`, `saturation` | Hunger and saturation |
| `armor` | Armor points |
| `level`, `xp` | Experience level, and progress to the next one from 0 to 1 |
| `sprinting`, `sneaking`, `flying`, `on_ground`, `in_water`, `burning` | True or false |
| `slot` | Your selected hotbar slot, 1 to 9 |

## Items

`held`, `offhand`, `helmet`, `chestplate`, `leggings` and `boots` are what you're holding and wearing, and `armor_items` lists the armor you have on. Each has:

| Part | Is |
| --- | --- |
| `.name` | Its name |
| `.id` | Its id, like `minecraft:diamond_sword` |
| `.count` | How many are in the stack |
| `.durability`, `.max_durability`, `.damage` | Durability left, total and used. Missing for items that don't wear out |
| `.icon` | The item as an icon. Show its durability bar with `{helmet.icon \| bar}` |

`item("emerald")` is any item by id. Its `.count` is how many you carry in total, and it has `.name`, `.id` and `.icon` too.

## Potion effects

`effects` lists your active effects, and `effect("speed")` is one effect, or nothing if it isn't active. Each has:

| Part | Is |
| --- | --- |
| `.name`, `.id` | Its name, like Speed, and id, like `speed` |
| `.level`, `.amplifier` | Its level from 1, and from 0 |
| `.time` | Time left, like `1:30` or `**:**` |
| `.duration`, `.ticks` | Seconds and ticks left |
| `.infinite`, `.ambient` | True or false |
| `.category` | `beneficial`, `harmful` or `neutral`, also as `.beneficial` and `.harmful` |
| `.color` | The effect's own color |
| `.icon` | Its icon |

## World and game

| Name | Is |
| --- | --- |
| `time`, `day` | The in-game time, like `06:00`, and day |
| `weather` | `clear`, `rain` or `thunder` |
| `fps` | Frames per second, with `.average` `.median` `.consistency` (%), `.p95` `.p99` (frame times in ms) and `.live` |
| `ping` | Ping in ms |
| `server`, `singleplayer` | The server address, `127.0.0.1` in singleplayer like the Server Address element |
| `memory` | Memory used in GB, with `.used` `.max` and `.percent` |
| `entities` | Rendered and total entities, like `42/120`, with `.rendered` `.total` |
| `cps` | Clicks per second like `8 \| 3`, with `.left` `.right` |
| `combo` | Your current combo |
| `reach` | Your last hit's reach, measured to the exact spot your crosshair hit. Shows 2 decimals like the Reach element, or every digit with `{reach \| exact}` |
| `tab` | A [column](styling.md#columns) break |

## Functions

| Function | Does |
| --- | --- |
| `round(n, places)`, `fixed(n, places)`, `floor(n)`, `ceil(n)`, `abs(n)` | Round numbers, to as many as 16 places |
| `exact(n)` | Every digit a number has |
| `min(...)`, `max(...)`, `clamp(n, low, high)`, `sqrt(n)`, `pow(n, p)` | Math |
| `signed(n)` | A number with `+` in front when positive |
| `roman(n)` | Roman numerals |
| `duration(seconds)` | Seconds as `1:30` or `1:01:01` |
| `every(value, seconds)` | Only lets the value change every so many seconds. See [update speed](styling.md#update-speed-and-smoothing) |
| `smooth(value, seconds)` | Counts to a new number over half a second, or the time you give |
| `number(text)`, `text(value)` | Turn text into a number and back |
| `upper`, `lower`, `title`, `trim` | Change text |
| `plain(text)` | Text without color codes |
| `replace(text, old, new)` | Replace part of the text |
| `truncate(text, length)` | Cut text short with `...` |
| `pad_left(text, length, char)`, `pad_right(...)` | Pad text, like `pad_left(7, 3, "0")` for `007` |
| `repeat(text, times)` | Repeat text, handy for bars like `repeat("\|", health)` |
| `len(x)`, `contains(x, y)` | Length, and whether one contains the other |
| `join(list, separator)` | A list as text |
| `first`, `last`, `reverse`, `take(list, n)` | Pick from a list |
| `sort(list, part)` | Sort a list, like `sort(effects, "duration")` |
| `where(list, part, value)` | Keep only matching items, like `where(effects, "harmful")` |
| `range(n)`, `range(from, to)` | A list of numbers |
| `switch(value, a, x, b, y, default)` | `x` if the value is `a`, `y` if it is `b`, otherwise `default` |
| `now(pattern)` | The real time, like `now("HH:mm:ss")` |
| `color`, `rgb`, `hsv`, `blend`, `gradient`, `rainbow` | [Colors](styling.md#colors) |
| `size(n)` | [Text size](styling.md#sizes) |
| `bar(icon)` | An item's durability bar on its [icon](styling.md#icons) |
| `item(id)`, `effect(id)` | [Items](#items) and [effects](#potion-effects) |
