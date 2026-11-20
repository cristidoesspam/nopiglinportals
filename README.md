# No Piglin Portals

Piglins, piglin brutes and zombified piglins can no longer go through nether portals, and overworld portals
stop spawning zombified piglins. You can allow them again in specific chunks, or flip the whole thing around.
Fabric mod for **Minecraft 26.3**.

## Install

1. Fabric Loader 0.19.5 or newer for 26.3 (Java 25)
2. [Fabric API](https://modrinth.com/mod/fabric-api) `0.163.0+26.3` or newer
3. Drop `nopiglinportals-1.1.0.jar` into your `mods` folder (delete any older `nopiglinportals` jar first)

Only needed on the server (or in singleplayer). Players do not need it on their client. There is no
config screen: everything is a command, saved per world.

## How it works

A blocked mob that touches a nether portal just stands in it. Nothing teleports and nothing breaks.

Which rule applies is decided by the **chunk the portal block is in**, in the dimension the mob is leaving.
So to let piglins out of a Nether portal, mark the chunk of that Nether portal. Each chunk has either
an explicit setting (allow or block) or follows the default.

By default piglins, piglin brutes and zombified piglins are blocked everywhere. Zombified piglins matter most:
they are the ones that wander into portals in the Nether, and overworld portals also **spawn** them on their
own now and then, which looks exactly like piglins coming through. In a blocked chunk a portal no longer
spawns them. Take a mob off the list with `/piglinportal mobs remove <mob>` if you only want some stopped.

If you used version 1.0.0, it only blocked piglins and brutes. A world that already has a saved
`nopiglinportals.json` keeps its own mob list, so run `/piglinportal mobs add minecraft:zombified_piglin` there.

## Commands

All of these need operator permission (gamemaster).

| Command | What it does |
| --- | --- |
| `/piglinportal` | Status: on/off, default, affected mobs, how many mobs it has kept out of portals since the server started, and the rule for the chunk you stand in |
| `/piglinportal chunk allow [<x> <z> [<radius>]]` | Piglins may use portals in this chunk |
| `/piglinportal chunk block [<x> <z> [<radius>]]` | Piglins are blocked in this chunk, even if the default is allow |
| `/piglinportal chunk reset [<x> <z> [<radius>]]` | Remove the setting, the chunk follows the default again |
| `/piglinportal chunk status [<x> <z>]` | What applies to one chunk |
| `/piglinportal chunk list` | Every chunk with a setting in your current dimension |
| `/piglinportal chunk clear` | Remove every chunk setting in your current dimension |
| `/piglinportal default block` | Chunks without a setting block piglins (the starting state) |
| `/piglinportal default allow` | Chunks without a setting let piglins through, so you block only the chunks you pick |
| `/piglinportal disable` / `enable` | Master switch. Chunk settings are kept while it is off |
| `/piglinportal mobs list` | Which mobs are affected |
| `/piglinportal mobs add <mob>` / `remove <mob>` | Change which mobs are affected |

`<x> <z>` are block coordinates (`~ ~` works) and the command uses the chunk that contains them. Leave them
out to use the chunk you are standing in. `<radius>` is in chunks (0 to 16), so `radius 1` covers the 3x3
chunks around that one. Use `/execute in minecraft:the_nether run piglinportal ...` to edit another dimension
from afar. `F3 + G` shows chunk borders in game.

## Notes

- A piglin riding a strider (or anything else) is blocked along with its mount. Anything with a player
  riding it is never blocked, so you can still carry a piglin through yourself.
- Settings live in `<world>/data/nopiglinportals.json`. It is safe to edit while the server is stopped.
- Zombified piglins that already exist stay where they are. Only new spawns at portals and new portal trips
  are stopped.
- If a mob is blocked it just stands in the portal. The status counter in `/piglinportal` is the quick way to
  check the mod is doing something.

## Build

Needs JDK 25.

```
./gradlew build
```

The jar is in `build/libs/`.
