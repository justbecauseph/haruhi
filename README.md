# Haruhi

Haruhi is a Fabric mod for Minecraft 26.2 that adds scriptable NPCs. You write NPC profiles and their conversations as
one JSON content bundle, load it on the server as a stored revision, and spawn NPCs that open those conversations when a
player right-clicks them. Staff can also bind a conversation to a placed block or to an existing entity, such as a
vanilla villager. Players see a visual-novel dialogue screen, and NPCs draw with Blockbench models through GeckoLib.
Each player's dialogue flags and visit counts are stored in memory or in PostgreSQL.

## Features

- Dialogue graphs are JSON: nodes with text and choices, routers that branch by weight or on a condition (one line
  for a newcomer, another for a returning player), conditions that hide or gray out choices, and actions that run on
  entering a node or picking a choice. A bundle is validated in full before it can become active, and the validator
  lists every problem it finds, one per line.
- `/haruhi content` imports, validates, activates and rolls back bundles while the server runs. Each revision is
  stored with the SHA-256 digest of its canonical JSON.
- The visual-novel screen types out the text, shows a portrait or a 3D headshot of the speaker, and takes choices by
  click or the number keys 1 to 9. While it is open, most HUD elements under the text box are hidden and the chat
  moves above it.
- The NPC entity stays where it is spawned, takes no damage except from `/kill` and the void, turns to face the player
  who talks to it, and scales from 1/16 up to 4 times normal size.
- `/haruhi bind` attaches a graph to a placed block or to any existing entity, such as a vanilla villager or an armor
  stand. Using it opens the conversation in place of its vanilla interaction, so a bound chest does not open and a bound
  villager does not trade. `/haruhi bindings` lists them and marks those whose graph the active content lacks.
- Clients draw NPCs with GeckoLib models and animations, or with the vanilla player model when GeckoLib is not
  installed.
- The server caches flags and visit counts per character. Flag changes are written to storage in batches, visit counts
  at each conversation.
- Built-in conditions test flags, visit counts, items, advancements, vanilla predicates, scoreboard tags, permission
  level, time of day and weather. Built-in actions give loot-table rewards once per character, play sounds and
  animations, send chat messages, jump between nodes and graphs, and run commands on an allowlist.
- Other mods can register their own condition, action and choice-provider types.
- A Gradle task converts a Blockbench `.bbmodel` project into GeckoLib model, animation and texture files.

## Requirements

From `src/main/resources/fabric.mod.json` and `gradle.properties`:

| Dependency | Version | Needed on |
|---|---|---|
| Minecraft | 26.2 (`~26.2`) | server and client |
| Java | 25 or newer | server and client |
| Fabric Loader | 0.19.3 or newer | server and client |
| Fabric API | any version; the build uses 0.158.0+26.2 | server and client |
| GeckoLib | 5.5.5 or newer, recommended | client only |

The server never loads GeckoLib. A client without it draws every NPC as the vanilla wide player model with the
profile's texture as its skin, and named animations do not play. Install the Haruhi jar on every client as well as
the server; the [server guide](docs/server-guide.md#which-side-needs-what) explains why.

## Known limitations

- Every player needs Haruhi installed. The server falls back to vanilla dialogs for a client without Haruhi's dialogue
  channel, but Fabric API's registry sync keeps such a client from joining in the first place.
- An NPC's hitbox is 0.6 by 1.8 blocks times its profile's scale, whatever the size of its model.
- While the dialogue screen is open, the multiplayer locator bar's waypoint markers and the action-bar message still
  draw under the text box. The experience and jump bars, the rest of the locator bar, the hotbar, the status bars, the
  experience level, the held-item name and the crosshair are hidden.
- Models, textures, animations and portraits are client assets. The server sends only their ids, so each client needs
  them from a resource pack or a mod.
- The default in-memory storage loses every flag, visit count and imported revision when the server stops.
- `/haruhi bindings` lists bound entities only while they are loaded, because an entity binding is saved on the entity.
  A block binding applies only while the block it was made on is at its position; see the
  [server guide](docs/server-guide.md#binding-behaviour) for breaking and replacing a bound block.

## Build from source

You need JDK 25. The Gradle wrapper (Gradle 9.5.1) downloads everything else.

```sh
git clone https://github.com/justbecauseph/haruhi
cd haruhi
./gradlew build        # on Windows: .\gradlew.bat build
```

`build` compiles the mod, runs the unit tests and runs the server GameTests. `./gradlew assemble` builds the jar
without running any tests.

The mod jar is `build/libs/haruhi-0.2.0.jar`; `build/libs/haruhi-0.2.0-sources.jar` holds the sources. The PostgreSQL
JDBC driver 42.7.7 and HikariCP 7.1.0 are nested inside the mod jar (jar-in-jar, under `META-INF/jars/`), so a server
needs nothing else to use PostgreSQL.

## Quick start for a server operator

1. Put `haruhi-0.2.0.jar` and Fabric API in the server's `mods/` folder. Give each player the same two jars, plus
   GeckoLib 5.5.5, for their client's `mods/` folder.
2. Start the server. The first start writes `config/haruhi.json` (in-memory storage) and `config/haruhi-dialogue.json`
   (no command actions allowed), and activates the seed bundle built into the jar. The log shows:

   ```
   Haruhi storage: in-memory backend. Flags, visits and content revisions are lost when the server stops.
   Activated content revision 1 from startup:seed
   ```

3. Join as an operator with permission level 2 or higher, and spawn the seed's greeter three blocks in front of you:

   ```
   /haruhi npc spawn haruhi:example/greeter ^ ^ ^3
   ```

   The reply reads `Spawned NPC haruhi:example/greeter in minecraft:overworld at <x> <y> <z> (entity <id>)`, and the
   NPC turns to face you.
4. Right-click the NPC. Its first node picks one of two greetings at random. On "Welcome to Maraha.", the choice
   "Thanks." sets the `met` flag, and "Have we met?" stays grayed out until that flag is set.
5. Read the flag back. Quote the namespace, because it contains `:` and `/`:

   ```
   /haruhi flags get @s "haruhi:example/greeter" met
   ```

The seed profile names the model `haruhi:example/greeter` and a portrait texture, and the jar ships neither, so the
greeter draws as the built-in humanoid model with the Steve skin and the dialogue screen shows a 3D headshot in place
of the portrait.

With the default in-memory storage, flags, visit counts and imported revisions are gone after a restart. Switch to
PostgreSQL before players depend on them: see [Storage configuration](docs/server-guide.md#storage-configuration).

## Documentation

| Guide | For | Covers |
|---|---|---|
| [Server guide](docs/server-guide.md) | server operators | installing, configuration files, storage and PostgreSQL, every `/haruhi` command, block and entity bindings, content revisions, flag persistence, troubleshooting |
| [Content guide](docs/content-guide.md) | content writers | the bundle format, NPC profiles, dialogue graphs, routers that branch on conditions, graphs bound to blocks and entities, built-in conditions and actions |
| [Bundle JSON Schema](docs/bundle.schema.json) | content writers | a JSON Schema of the bundle format and built-in arguments for editors; setup is in the content guide |
| [Models and animations](docs/models-and-animations.md) | model makers | Blockbench models, the `.bbmodel` converter, textures, animations, portraits |
| [Developer guide](docs/developer-guide.md) | mod developers | architecture, network payloads, the extension API, building and testing, repository layout |

`AGENTS.md` is the brief for coding agents working in this repository.

## Licence

Mozilla Public License 2.0. The full text is in [LICENSE](LICENSE).
