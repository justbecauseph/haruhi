# Server guide

For operators who install and run Haruhi. Back to the [README](../README.md). Writing bundles is in the [content
guide](content-guide.md); models and textures are in [models and animations](models-and-animations.md).

- [Known limitations](#known-limitations)
- [Install](#install)
- [Configuration files](#configuration-files)
- [Storage configuration](#storage-configuration)
- [PostgreSQL setup and migrations](#postgresql-setup-and-migrations)
- [Command actions configuration](#command-actions-configuration)
- [Client configuration](#client-configuration)
- [Content at startup](#content-at-startup)
- [Commands](#commands)
- [Content revisions](#content-revisions)
- [Flags and visits](#flags-and-visits)
- [Operating PostgreSQL](#operating-postgresql)
- [Troubleshooting](#troubleshooting)

## Known limitations

- Every client needs Haruhi. The vanilla dialog fallback exists, but Fabric API's registry sync keeps a client without
  Haruhi from joining (see [Which side needs what](#which-side-needs-what)).
- An NPC's hitbox is 0.6 by 1.8 blocks times its profile's `scale`, whatever the size of its model.
- While the dialogue screen is open, the waypoint markers of the multiplayer locator bar still draw under the text
  box. Haruhi hides Fabric API's info-bar element, which covers only the bar's background; vanilla draws the markers in
  a separate step.
- A revision carries only the ids of models, textures, animations and portraits. Each client needs the files from a
  resource pack or a mod.
- The default in-memory storage loses everything when the server stops.
- A server reads the active revision only at startup, so an activation on one server does not reach another server
  that shares its schema until that server restarts.
- Flag changes reach storage in batches every 100 ticks (5 seconds at 20 ticks per second), so a crash loses the
  changes made since the last batch.
- `/haruhi bindings` lists bound entities only while they are loaded; an entity binding is saved on the entity, and
  nothing indexes it elsewhere.
- Bindings are not synced to clients, so a client may briefly show its own guess of a bound block's vanilla use, such
  as a door swinging open, before the server puts it back.

## Install

Build the jar as the [README](../README.md#build-from-source) describes, then copy `build/libs/haruhi-0.1.0.jar` into
the `mods/` folder of the server and of every client.

### Which side needs what

| Jar | Server | Client |
|---|---|---|
| `haruhi-0.1.0.jar` | required | required |
| Fabric API | required | required |
| GeckoLib 5.5.5 or newer | not used | recommended |

The server never loads GeckoLib; the build fails if code outside the client package refers to it. A client without
GeckoLib draws every NPC as the vanilla wide player model with the profile's `texture` as its skin, ignores model and
animation ids, and plays no animations.

Install Haruhi on every client. Haruhi registers the entity type `haruhi:npc`. Fabric API's registry sync disconnects a
client that has no Fabric API, or that lacks a registry entry the server has, before the player joins. This comes from
reading Fabric API's source; it was not tested with a live connection.

### Players without the Haruhi screen

The server sends the visual-novel screen only to a client that registered Haruhi's `haruhi:dialogue_node` channel. Any
other client gets Minecraft's built-in dialog screen instead:

- The title is the speaker's name, or "Dialogue" when the node has no speaker.
- Each visible choice is a button. A grayed-out choice keeps its button and shows its `unavailable_message` as the
  button's tooltip; pressing it shows the node again.
- A node with choices has a Cancel button, and Escape also ends the conversation.
- A node without choices has a Continue button, and Escape does not close it.
- There is no portrait, no typed-out text and no node animation.

Registry sync keeps a client without Haruhi from joining, so a normal player never reaches this fallback.

### Singleplayer

Haruhi's main entrypoint runs on clients as well, so a singleplayer world uses `config/haruhi.json` and
`config/haruhi-dialogue.json` from the client's own `config/` folder. Storage is closed when a world stops and built
again when the next world starts.

## Configuration files

The three JSON configuration files are read once, the server-side ones when the mod initialises and the client one when
the client starts. Restart to apply a change.

| File | Side | Read | Created when missing |
|---|---|---|---|
| `config/haruhi.json` | server | at mod initialisation | yes, with the defaults |
| `config/haruhi-dialogue.json` | server | at mod initialisation | yes, with the defaults |
| `config/haruhi-client.json` | client | at client initialisation | yes, with the defaults |
| `config/haruhi/bundle.json` | server | at server start, only when no stored revision is active | no |
| `config/haruhi/bundles/` | server | by `/haruhi content import` and `validate` | no; create the folder yourself |

## Storage configuration

`config/haruhi.json` picks the storage backend. The file written on first start:

```json
{
  "storage": {
    "type": "memory",
    "jdbc_url": "jdbc:postgresql://localhost:5432/haruhi",
    "username": "haruhi",
    "password_env": "HARUHI_DB_PASSWORD",
    "pool_size": 4,
    "schema": "haruhi"
  }
}
```

| Key | Default | Rule |
|---|---|---|
| `type` | `"memory"` | `"memory"` or `"postgres"` |
| `jdbc_url` | `"jdbc:postgresql://localhost:5432/haruhi"` | must start with `jdbc:postgresql:` when `type` is `postgres`; only the PostgreSQL backend reads it |
| `username` | `"haruhi"` | must not be blank when `type` is `postgres` |
| `password_env` | `"HARUHI_DB_PASSWORD"` | the name of the environment variable that holds the password; must not be blank when `type` is `postgres` |
| `pool_size` | `4` | a whole number from 1 to 100: the number of PostgreSQL connections and of storage threads; the in-memory backend does not use it |
| `schema` | `"haruhi"` | 1 to 63 lowercase letters, digits or underscores, not starting with a digit |

Validation:

- The only top-level key is `storage`. A file without it uses every default, which is the in-memory backend.
- A missing key takes its default. An unknown key, at the top level or inside `storage`, is an error, so a misspelt key
  cannot fall back to a default without telling you.
- A `password` key is refused. The password never goes in the file.
- `pool_size` and `schema` are checked for both backends.
- An invalid file stops the game from starting, with `Haruhi storage configuration <path> is invalid: <reason>`. The
  reason names the problem and never echoes a value that could be a secret.

### In-memory backend

`"type": "memory"` keeps everything in the server's memory and logs at startup:

```
Haruhi storage: in-memory backend. Flags, visits and content revisions are lost when the server stops.
```

When the server stops, every flag, visit count and stored revision is gone. Each start therefore finds no active
revision and imports `config/haruhi/bundle.json` or the seed bundle again (see [Content at startup](#content-at-startup)).
A revision you imported and activated with commands lasts only until the next restart. Use this backend for testing.

### PostgreSQL backend

`"type": "postgres"` stores everything in the configured schema. Set the password in the environment variable that
`password_env` names, in the environment the server process starts in:

```sh
export HARUHI_DB_PASSWORD='change-me'
# then start the server from this shell, or set the variable in your service manager's unit file
```

The variable is read when the storage is built: when the mod initialises, and in singleplayer again each time a world
starts after another has stopped. If it is unset or empty when the mod initialises, the game does not start:
`Haruhi storage is set to PostgreSQL, but the environment variable HARUHI_DB_PASSWORD is not set`.

The startup log names the schema: `Haruhi storage: PostgreSQL backend, schema haruhi`.

Connection settings Haruhi applies:

- A HikariCP pool named `haruhi-storage`, with at most `pool_size` connections. Storage work runs on the same number of
  threads, never on the server thread.
- Every connection uses `schema` as its default schema, so the tables need no schema prefix and the database role's
  `search_path` does not matter.
- `socketTimeout=10` (seconds) and `tcpKeepAlive=true`. A read that gets no answer fails after 10 seconds instead of
  holding a connection forever. A `socketTimeout` or `tcpKeepAlive` parameter in `jdbc_url` takes priority; a
  `socketTimeout` must be 5 to 2147483 seconds, or the game does not start.
- Server-side `lock_timeout` of half the socket timeout, rounded down, and `statement_timeout` of the socket timeout less
  2 seconds (5 and 8 seconds by default). On a server that is answering, these cancel and roll back a statement that
  waits on a lock or runs too long, before the client gives up on it. They do not limit the commit: a slow disk or a
  synchronous standby can make the commit outlast the socket timeout. They do not help when the network stalls.
- A failure with SQLState 08006 (connection lost or socket timeout) leaves the outcome unknown. The server may still
  run the statement or commit it after the client has given up.
- An `options` parameter in `jdbc_url` is kept, and these two limits are added after it, so they override a
  `lock_timeout` or `statement_timeout` it sets. A blank `options` (`?options=`, or a bare `?options`) does not remove
  the limits.

The pool is built when the mod initialises and opens its first connection then, so if PostgreSQL cannot be reached,
the game does not start. The migrations run when the server starts.

## PostgreSQL setup and migrations

### Database and role

A role that owns the Haruhi schema needs no other rights. Run as a superuser, in `psql`:

```sql
CREATE ROLE haruhi LOGIN PASSWORD 'change-me';
CREATE DATABASE haruhi;
\c haruhi
CREATE SCHEMA haruhi AUTHORIZATION haruhi;
```

The test suite checks that a role which owns the schema migrates it without `CREATE` on the database, and that a role
with no `CREATE` right at all passes the migration step against a schema that is already migrated. If the schema does
not exist, the migration step creates it, which needs `CREATE` on the database. The tests run against PostgreSQL 18.4.

### Migrations

- The SQL migrations ship in the jar under `haruhi/migrations/`. This build has one, `V1__init.sql`.
- They run when the server starts, before any content loads. The server start waits for them.
- Each migration runs in its own transaction, together with its row in the `schema_version` table. An advisory lock
  makes two servers that start on one database apply each migration once. A migration already recorded is skipped.
- A failure stops the server start: `Haruhi storage could not migrate schema <schema>: <reason>`.
- A schema migrated by a newer Haruhi build is refused, so an older jar cannot run against it:
  `Schema <schema> is at version <n>, newer than this build's <m>`.

The schema holds four tables:

| Table | Key | Holds |
|---|---|---|
| `flags` | `character_id`, `namespace`, `flag` | one row per set flag: `value`, `updated_at`. Clearing a flag deletes its row. |
| `visits` | `character_id`, `graph`, `npc` | `count` and `last_visit` per graph and NPC profile |
| `content_revision` | `id` | `digest` (unique), `bundle` (canonical JSON), `author`, `created_at`, `activated_at`, `activated_by`, `active` |
| `schema_version` | `version` | `description`, `applied_at` |

A partial unique index on `content_revision (active) WHERE active` lets only one row be active.

## Command actions configuration

`config/haruhi-dialogue.json` controls the `haruhi:command` action. The file written on first start:

```json
{
  "command_permission_level": 2,
  "command_allowlist": []
}
```

| Key | Default | Rule |
|---|---|---|
| `command_permission_level` | `2` | an operator permission level from 0 to 4 |
| `command_allowlist` | `[]` | command names, compared with the first word of each command, without the slash |

- With the empty default list, a bundle that uses `haruhi:command` fails validation. A command whose first word is not on
  the list fails with `<graph>#<node>: haruhi:command: command '<word>' is not on the allowlist in haruhi-dialogue.json`.
- The command runs as the player, from the player's position, at `command_permission_level` instead of the player's own
  level. A leading `/` is removed.
- Only the first word is checked. Allowing `execute`, or any other command that runs commands, lets a bundle run
  anything at that level.
- Unknown keys are ignored. A file that cannot be read or parsed, or a level outside 0 to 4, logs
  `Could not read <path>; command actions are disabled` and the defaults apply.
- The allowlist is checked whenever a bundle is validated, and that includes the active stored revision at every
  start. If you remove a command that the active revision uses, the next start finds the revision invalid and runs with
  no content (see [Content at startup](#content-at-startup)).

Example, to let bundles run `give` and `tellraw` at game-master level:

```json
{
  "command_permission_level": 2,
  "command_allowlist": ["give", "tellraw"]
}
```

## Client configuration

`config/haruhi-client.json` lives on each player's client:

```json
{
  "text_speed": 40
}
```

| Key | Default | Rule |
|---|---|---|
| `text_speed` | `40` | characters per second that the dialogue screen types out; a positive number |

A missing, zero, negative or non-numeric value, or a file that is not JSON, uses 40 and logs a warning such as
`haruhi-client.json has no positive text_speed; using 40.0`. Players can click, or press Space or Enter, to show the
whole line at once.

## Content at startup

When the server has started, Haruhi picks one source of content:

1. The active stored revision, if storage has one. It is validated again against this server.
2. Otherwise `config/haruhi/bundle.json`, if that file exists. It is validated, stored as a new revision with the
   author `startup:config/haruhi/bundle.json`, and activated.
3. Otherwise the seed bundle in the jar (`haruhi/seed_bundle.json`). It is stored with the author `startup:seed` and
   activated.

Only the chosen source is tried. If it fails validation, the log lists every problem and the server runs with no
content: no NPC profiles and no dialogue graphs. The commands still work, so you can activate a valid revision
without restarting.

The load runs in the background after the server reports it has started. Until it finishes, no content is active.

With PostgreSQL, the first start stores and activates `bundle.json` or the seed. From then on an active revision
exists, so later edits to `config/haruhi/bundle.json` have no effect. Deploy new content with
[`/haruhi content import` and `activate`](#content-revisions). With the in-memory backend, every start goes through step
2 or 3.

## Commands

Every `/haruhi` command needs permission level 2 (game master) or higher; a GameTest walks the command tree to check
this. Replies are plain text. Failures come back as red lines, one line per problem.

The success messages of `/haruhi dialogue`, `/haruhi flags set` and `clear`, `/haruhi npc spawn`, `set` and
`remove`, and `/haruhi bind` and `unbind` are also shown to other operators, as vanilla commands do. The other commands
reply only to you.

Arguments that are a plain string, such as the flag namespace, must be quoted when they contain anything other than
letters, digits, `_`, `-`, `.` and `+`. Flag namespaces such as `haruhi:example/greeter` contain `:` and `/`, so write
`"haruhi:example/greeter"`. Ids such as graph and profile ids need no quotes.

### /haruhi content

| Command | What it does |
|---|---|
| `/haruhi content validate <file>` | Reads `config/haruhi/bundles/<file>` and validates it. Stores nothing. |
| `/haruhi content import <file>` | Validates the file and stores it as a new revision. Does not activate it. The author is your name. |
| `/haruhi content list` | Lists the 20 newest revisions by creation time. |
| `/haruhi content activate <id>` | Validates the stored revision again and makes it active. |
| `/haruhi content rollback` | Activates the most recently activated revision other than the active one. |
| `/haruhi content status` | Shows the active revision id, its digest, and how many NPC profiles and graphs it has. |

`<file>` takes the rest of the line, so it may contain spaces and subfolders. Example replies, with made-up digests;
`validate` and `status` print the whole digest, `import` and `list` its first 12 characters:

```
> /haruhi content validate town.json
Valid: 3 NPCs, 4 graphs, digest 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08

> /haruhi content import town.json
Stored revision 2 (digest 9f86d081884c). It is not active. Run /haruhi content activate 2 to use it.

> /haruhi content list
Revision 2 2026-11-04T10:15:02.481920Z by Steve digest 9f86d081884c
Revision 1 2026-11-04T10:00:00.120Z by startup:seed digest 3b1f6f3a2c04 (active)

> /haruhi content activate 2
Activated revision 2.

> /haruhi content status
Active revision: 2
Digest: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
NPCs: 3, graphs: 4

> /haruhi content rollback
Rolled back to revision 1.
```

When there are none, `list` replies `No content revisions are stored.`

### /haruhi dialogue

| Command | What it does |
|---|---|
| `/haruhi dialogue open <graph> [<targets>]` | Opens a graph of the active content for the target players, or for you. The conversation has no NPC, so it does not end when the player walks away. |
| `/haruhi dialogue close [<targets>]` | Ends the conversation of the target players, or yours, including one still waiting for the player's flags to load. |

Without `<targets>`, the command must be run by a player. Replies:

```
Opened haruhi:example/greeter for 1 player(s)
Closed 1 conversation(s)
Dialogue haruhi:example/missing is not in the active content
```

Opening a conversation closes any conversation the player already has.

### /haruhi flags

| Command | What it does |
|---|---|
| `/haruhi flags get <player> <namespace> <flag>` | Shows the flag's value; an unset flag reads 0. |
| `/haruhi flags set <player> <namespace> <flag> [<value>]` | Sets the flag; `<value>` defaults to 1. |
| `/haruhi flags clear <player> <namespace> <flag>` | Clears the flag. |

The player must be online, and the flag must be declared in the active content. Changes go through the same batched
writes as dialogue actions (see [Flags and visits](#flags-and-visits)). Replies:

```
> /haruhi flags get Steve "haruhi:example/greeter" met
Steve haruhi:example/greeter met = 1

> /haruhi flags set Steve "haruhi:example/greeter" times_talked 3
Set times_talked to 3 for Steve

> /haruhi flags clear Steve "haruhi:example/greeter" met
Cleared met for Steve
```

Failures: `That flag is not declared in the active content`, and `Steve's dialogue data is still loading; try again`
right after the player joins.

### /haruhi npc

| Command | What it does |
|---|---|
| `/haruhi npc spawn <profile> [<pos>]` | Spawns an NPC with a profile of the active content at your position or `<pos>`, turned to face you. Tab completion lists the profile ids. |
| `/haruhi npc set <entity> <profile>` | Gives an existing NPC another profile. |
| `/haruhi npc remove <entity>` | Removes an NPC. |
| `/haruhi npc list` | Lists the NPCs in loaded chunks of every dimension. |

`<entity>` must select one entity, for example `@e[type=haruhi:npc,sort=nearest,limit=1]`. Replies:

```
> /haruhi npc spawn haruhi:example/greeter ^ ^ ^3
Spawned NPC haruhi:example/greeter in minecraft:overworld at 12.50 64.00 -3.50 (entity 214)

> /haruhi npc list
haruhi:example/greeter in minecraft:overworld at 12.50 64.00 -3.50 (entity 214)
```

Failures: `Unknown NPC profile <id>` (not in the active content), `Invalid position for spawn` (more than 30,000,000
blocks out horizontally or 20,000,000 vertically, the bounds `/summon` checks), and `No NPC entity is selected` (the
entity is not an NPC). With no NPC loaded, `list` replies `No NPCs are loaded`.

`/summon haruhi:npc` is disabled, because it would make an NPC with no profile.

### NPC behaviour

- Right-clicking an NPC with the main hand turns it toward the player and opens its profile's `dialogue` graph. An NPC
  whose profile has no `dialogue`, or whose profile is missing from the active content, does nothing.
- The conversation ends when the player moves more than 8 blocks from the NPC, changes dimension, dies or leaves, or
  when the NPC is removed.
- An NPC does not move, fall, despawn or get pushed, and pistons, explosions, fishing rods and splash potions do not
  affect it. It takes no damage except from `/kill` and the void, from creative players too. Only a teleport or a
  direct position change moves it.
- The hitbox is 0.6 by 1.8 blocks times the profile's `scale`, whatever the size of the model. Clicks hit the hitbox,
  not the model. The client places the name tag just above the model.
- The profile id and the last look are saved with the entity, so an NPC shows its look as soon as it loads.

### /haruhi bind, unbind and bindings

A binding attaches a graph of the active content to a placed block or to an existing entity that is not a Haruhi NPC: a
vanilla villager, an armor stand, a mob, an `interaction` entity. A player who uses that block or entity opens the
conversation, as with an NPC.

| Command | What it does |
|---|---|
| `/haruhi bind block <pos> <graph> [<speaker>]` | Binds the block at `<pos>` in your dimension. Replaces any binding the block had, on either half of a door, bed or double chest. |
| `/haruhi bind entity <targets> <graph> [<speaker>]` | Binds each selected entity. Players and Haruhi NPCs are skipped. |
| `/haruhi unbind block <pos>` | Removes the binding at `<pos>`, also in an unloaded chunk, or the binding of the block's other half (door, bed, double chest) in a loaded one. |
| `/haruhi unbind entity <targets>` | Removes the bindings of the selected entities. |
| `/haruhi bindings` | Lists the block bindings and loaded bound entities within 32 blocks of where the command runs, in its dimension. |
| `/haruhi bindings all` | Lists every block binding in every dimension, and every bound entity that is loaded. |

`<graph>` must be a graph of the active content, and `<speaker>`, when given, an NPC profile of it; tab completion
lists both. `<speaker>` names the character that speaks for the block or entity: its name and portrait show on nodes
that name no `speaker`, and visits count under it. Without it the block's or entity's own name shows, such as
"Lectern", "Librarian" or a name tag's name. `<pos>` must be a loaded block that is not air. Replies:

```
> /haruhi bind block 12 64 -3 haruhi:example/greeter
Bound minecraft:lectern at 12 64 -3 in minecraft:overworld to haruhi:example/greeter

> /haruhi bind entity @e[type=villager,sort=nearest,limit=1] haruhi:example/greeter haruhi:example/greeter
Bound minecraft:villager "Librarian" 4f3c2a1e-... in minecraft:overworld at 10.50 64.00 -1.50 to haruhi:example/greeter, speaker haruhi:example/greeter

> /haruhi bindings
Haruhi bindings within 32 blocks: 1 block, 1 loaded entity, 1 with problems
block minecraft:lectern at 12 64 -3 in minecraft:overworld -> haruhi:example/greeter [missing graph]
entity minecraft:villager "Librarian" 4f3c2a1e-... in minecraft:overworld at 10.50 64.00 -1.50 -> haruhi:example/greeter, speaker haruhi:example/greeter

> /haruhi unbind block 12 64 -3
Unbound minecraft:lectern at 12 64 -3 in minecraft:overworld from haruhi:example/greeter
```

The listing marks a binding that does not work as it was made: `[missing graph]` when the active content lacks its
graph, `[missing speaker profile]` when it lacks its speaker profile, and `[block is now <id>]` when another block
stands at the position. A block binding in an unloaded chunk is listed with `(chunk not loaded)`, unchecked for its
block. Entities are listed only while loaded, because entity bindings are saved with the entities themselves.

Failures: `Unknown dialogue graph <id>`, `Unknown NPC profile <id>`, `There is no block at <pos>`, `No block binding at
<pos>`, `None of the selected entities has a binding`, and `No entity was bound; skipped 1 player` (or `1 Haruhi NPC,
which talks through its profile`). An NPC's conversation comes from its profile's `dialogue`, so a Haruhi NPC cannot be
bound.

### Binding behaviour

- Using a bound block or entity with the main hand opens its graph, whatever the player holds, sneaking or not. It
  replaces the vanilla interaction: no container, trade screen, item use or block placement against the block. The
  held item's own use that a client sends right after (a bucket emptied, an ender pearl thrown, food eaten) is refused
  for that tick and the next, and the player's inventory is sent again. The off hand gets nothing, and so does a fake
  player from another mod (a machine that uses blocks). Spectators get the vanilla interaction.
- A binding whose graph the active content lacks gives the player nothing: no conversation and no vanilla interaction.
  Staff see it as `[missing graph]` in `/haruhi bindings`. A missing speaker profile only changes the name shown.
- A block conversation is tied to the block's position: it ends when the player moves more than 8 blocks from the
  block's centre (more for a player whose block reach is above 5 blocks: their reach plus 3, up to 64), changes
  dimension, dies or leaves. It does not end when the block is broken meanwhile.
- An entity conversation is tied to the entity: it ends when the entity is more than 8 blocks from the player, dies or
  changes dimension, or when the player dies or leaves. The entity keeps doing what it does, so give a mob that should
  stay put `NoAI`. Actions that need a Haruhi NPC, such as `haruhi:animation`, do nothing on other entities.
- The client does not know the bindings, so for a moment it may show its own guess of the vanilla result, such as a
  door swinging open; the server's block update puts it back.
- Block bindings are saved per dimension, in that dimension's `data/haruhi/block_bindings.dat`. A binding records the
  block that was there when it was bound and applies only while that block is at the position, in any state (a
  furnace that lights, a door that opens). A player who breaks the block removes its binding, and an operator who does
  is told so. Any other change (an explosion, a piston, fluid, `/setblock`, `/fill`) leaves the binding in place but
  inactive, shown as `[block is now <id>]`, until the same block is back; breaking another block that stands there
  meanwhile does not remove it. A binding does not follow a block a piston moves.
- A block that takes two positions has one binding for both: a door or tall plant (lower and upper half), a bed (foot
  and head), a double chest (its two chests). Bind either half; using either half opens the conversation, and binding
  the other half later replaces the binding. `/haruhi unbind block` takes either half's position while the chunk is
  loaded. The binding is saved at the position you bound, which is the one `/haruhi bindings` shows. Breaking a door,
  tall plant or bed removes the binding whichever half was broken. Breaking one chest of a double chest leaves the other
  chest standing, so a binding saved on that chest stays.
- An entity binding is saved on the entity, as the Fabric data attachment `haruhi:binding`. It moves with the entity,
  across dimensions too, and goes when the entity dies or is removed. It does not survive a conversion: a villager
  struck by lightning, a zombie villager that is cured, a mob that drowns into another kind.

## Content revisions

### Deploying a bundle

1. Create `config/haruhi/bundles/` and put the bundle file in it, for example `config/haruhi/bundles/town.json`.
2. `/haruhi content validate town.json` and fix any problems it lists.
3. `/haruhi content import town.json` stores it and replies with the new revision id.
4. `/haruhi content activate <id>` makes it active for the whole server.
5. `/haruhi content status` confirms it. If something is wrong, `/haruhi content rollback` goes back.

### How import and validate find files

The path is relative to `config/haruhi/bundles/`. A path that leaves that folder through `..`, or through a symbolic
link, is refused. The replies for a path outside the folder, a missing file and a link that leads out:

```
A bundle must be a file under /srv/minecraft/config/haruhi/bundles
There is no bundle file town.json under /srv/minecraft/config/haruhi/bundles
The bundle town.json links outside /srv/minecraft/config/haruhi/bundles
```

### What a revision stores

- The canonical form of the bundle: the parsed JSON with object keys sorted and whitespace removed, plus the
  lowercase hex SHA-256 of that text. Two files that differ only in key order or layout have the same digest.
- Importing content that is already stored returns the existing revision instead of a new one. If that revision is
  active, the reply is `Revision 1 (digest 3b1f6f3a2c04) already has this content and is active.`
- The author (who imported it), the creation time, and the latest activation time. Storage also records who last
  activated it (the `activated_by` column in PostgreSQL); `list` does not show it.
- Revisions cannot be changed, and no command deletes one.

### Activation and rollback

- `activate` loads the revision and validates it again against the server as it is now: its registries, the command
  allowlist, and the condition and action types other mods have registered. A revision that no longer validates is
  refused and stays inactive, and the reply lists the problems. `There is no content revision <id>.` means the id does
  not exist.
- Storage then marks it active, recording who did it and when, and the server swaps it in.
- Activations, rollbacks and the startup load run one at a time, in the order they were requested.
- `rollback` activates the inactive revision with the latest activation time. That is the previously active one, not
  the newest one created. Two rollbacks in a row bring back the revision you started from. With no other revision ever
  activated, it fails with `No earlier content revision has been activated.`

### What an activation reaches

- Every NPC in a loaded chunk, in every dimension, takes the new profile's name, look and scale straight away. An NPC
  in an unloaded chunk takes them on its first tick after it loads. An NPC whose profile id is missing from the new
  content keeps that id, draws with the default model and the Steve skin, gets its profile id as its custom name
  without an always-visible name tag, and does nothing when right-clicked.
- A conversation that is already open keeps running on the content it opened with until it ends. Every conversation
  opened afterwards, including one opened by `haruhi:open` from an old conversation, uses the new content.
- Bindings are not part of a revision and are not changed by an activation. Each use looks its graph up in the active
  content; a binding whose graph the new content lacks opens nothing until a revision has it again. Run `/haruhi
  bindings all` after an activation to find them.
- Stored flag values are not touched. The active revision's declarations decide which flags conditions, actions and
  `/haruhi flags` may use.
- Models, textures, animations and portraits are not part of a revision. They come from resource packs or mods on
  each client; see [models and animations](models-and-animations.md). A client that lacks one falls back to the
  default humanoid model, the Steve skin, or the 3D headshot.
- A server that shares the same database schema reads the active revision only when it starts.

## Flags and visits

Flags and visit counts belong to a character, which is the player's UUID. A flag is an integer; an unset flag reads 0.
A visit count is kept per dialogue graph and NPC profile, and counts the conversations the player opened with it. A
bound block or entity counts under the binding's speaker profile, and one bound without a speaker, like a graph opened
by command, under `haruhi:none`.

### Loading

- A player's flags and visit counts load in the background when the player joins.
- A conversation opened before they have loaded waits, then starts once they load, provided the player is still
  within 8 blocks of the NPC or bound entity, or within range of the bound block.
- A load that fails is logged, the waiting conversation is dropped, and the load is retried every 100 ticks (5 seconds)
  while the player stays online.

### Writing

| Change | In the server's cache | In storage |
|---|---|---|
| `haruhi:flag/set`, `flag/add`, `flag/clear`, `/haruhi flags set` and `clear` | at once | in the next batch: every 100 ticks (5 seconds), when the player leaves, and when the server stops |
| the `once` flag of `haruhi:give` | at once | written straight away; the items are handed over only after the write succeeds |
| a visit count | when the conversation opens | written straight away, in the background, at each open |

- A batch that fails is logged and its changes go into the next batch. A player who rejoins in the meantime still sees
  those changes, because the server applies the unwritten changes on top of what storage returns.
- When the server stops, it ends every conversation, writes every pending batch, and waits up to 10 seconds for them.
  Storage then closes and lets queued work run for up to 10 more seconds before dropping the rest.
- If the server process dies without stopping, the flag changes since the last batch, up to about 5 seconds' worth, are
  lost.
- A `haruhi:give` reward is never handed out twice. If the server stops or crashes after the `once` flag is written and
  before the items reach the player, the reward is lost. On a stop, the log names the reward and says to grant it by
  hand; a crash leaves no log line for it. A player who is dead or offline when the write completes gets the items
  once they are back online and alive, as long as the server keeps running.
- With the in-memory backend, nothing survives a stop.

## Operating PostgreSQL

### Backups

Everything Haruhi stores is in its schema; the bundle files under `config/haruhi/` are ordinary files, so back up that
folder too. Dump the schema with `pg_dump`, in the custom format that `pg_restore` reads (add `-h`, `-U` and any other
connection options your setup needs):

```sh
pg_dump -n haruhi -F c -f haruhi.dump haruhi
```

A dump taken while the server runs misses flag changes still waiting for the next batch, up to about 5 seconds' worth.
For a complete dump, stop the server first; it writes the pending batches as it stops.

To restore over the existing schema, stop the server, then:

```sh
pg_restore --clean -d haruhi haruhi.dump
```

### Inspecting

```sql
-- Revisions, with who activated each one last.
SELECT id, left(digest, 12) AS digest, author, created_at, activated_at, activated_by, active
FROM haruhi.content_revision ORDER BY id;

-- One player's flags.
SELECT namespace, flag, value, updated_at FROM haruhi.flags
WHERE character_id = '0f8fad5b-d9cb-469f-a165-70867728950e';
```

Change content and flags through the commands, not with SQL, while the server runs. The server reads a player's flags
only when the player joins, and reads the active revision only at startup, so a direct edit does not reach a running
server and can be overwritten by its next write.

### Several servers on one database

Give each server its own `schema` unless the servers are meant to share state. Migrations and activations are safe to
run from several servers at once, because advisory locks run them one at a time. Still, each server reads the active
revision only when it starts, and a player who plays on two servers that share a schema has two caches writing the
same flags: the last write of each flag wins.

## Troubleshooting

| Log message or reply | Meaning | What to do |
|---|---|---|
| `Haruhi storage configuration <path> is invalid: <reason>` | `config/haruhi.json` has an unknown key, a `password` key, or a bad value. The game does not start. | Fix the key named in the reason. |
| `Haruhi storage cannot read or write <path>` | The config folder is not readable or writable. | Check file permissions. |
| `Haruhi storage is set to PostgreSQL, but the environment variable HARUHI_DB_PASSWORD is not set` | The password variable is missing in the server's environment. | Set it where the server process starts. |
| `Haruhi storage could not migrate schema <schema>: <reason>` | The migration failed; the server does not start. | Check the database, the role's rights and the `jdbc_url`. |
| `... Schema <schema> is at version <n>, newer than this build's <m>` | A newer Haruhi build migrated this schema. | Run that build or a newer one. |
| `Active content revision <id> no longer validates, so no content is active:` and a list of problems | The active stored revision fails against this server, for example after a mod that registers a condition type was removed, or a command was taken off the allowlist. | Fix the cause and restart, or `/haruhi content activate` another revision, or `/haruhi content rollback`. |
| `Content bundle from startup:config/haruhi/bundle.json does not validate, so no content is active:` | `bundle.json` was chosen at startup and failed. | Fix the problems listed and restart, or import and activate a valid bundle. |
| `Content bundle from startup:seed does not validate, so no content is active:` | The built-in seed failed. A GameTest checks that the seed validates with the built-ins, so this points to a modified jar. | Rebuild the jar, or import and activate a valid bundle. |
| `Could not load the active content revision, so no content is active` | Storage failed when asked for the active revision. | Check the database; then restart or activate a revision. |
| `Could not store and activate the content bundle from <author>` | The startup bundle validated, but storing or activating it failed. | Check the database. |
| `Activated content revision <id>` or `Activated content revision <id> from startup:seed` | Startup content is active. | Nothing. |
| `Could not read <path>; command actions are disabled` | `config/haruhi-dialogue.json` is not valid JSON or has an out-of-range level. The defaults apply, so bundles that use `haruhi:command` fail validation. | Fix the file and restart. |
| `Could not load dialogue flags and visits for <uuid>; retrying in 100 ticks` | Storage failed to load a player's data. The player's waiting conversation was dropped. | Check the database; the load retries every 5 seconds. |
| `Could not write <n> dialogue flag change(s) for <uuid>; will retry` | A batch failed and goes into the next one. | Check the database. |
| `Dialogue flags did not finish writing within 10 seconds at shutdown` | Some flag changes may not have reached storage before the stop. | Check the database's health. |
| `Could not record a visit to <graph> for <uuid>` | A visit count did not reach storage. | Check the database. |
| `haruhi:give refers to loot table <id>, which the server does not have; nothing given` | The bundle names a loot table the server's data packs lack. | Add the loot table, or fix the bundle. |
| `haruhi:give could not hand <table> to <uuid> before the server stopped; the once flag is set, so grant it by hand` | A reward was waiting for a dead or offline player when the server stopped. | Give the items by hand. |
| `Dropped the haruhi:give of <table> for <uuid>: the server has stopped after its once flag was stored, so it must be granted by hand` | The flag write finished after the server stopped. | Give the items by hand. |
| `Dialogue action failed at <graph>#<node>; ending the conversation` | An action threw an error; the stack trace follows. | Fix the action in the bundle, or report the error. |
| `A router condition failed at <graph>#<node>; ending the conversation` or `A choice condition or provider failed at <graph>#<node>; ending the conversation` | A condition in a router branch's `only_if`, or a choice's condition or provider, threw an error; the stack trace follows. | Fix the condition in the bundle, or report the error to the mod that registers it. |
| `Dialogue <graph> entered more than 64 nodes in one step; ending the conversation` | Routers or `haruhi:goto` actions loop without showing a node. | Break the loop in the graph. |
| `Dialogue <graph> would open past 8 graphs in one step; not opened` | A chain of `haruhi:open` actions is longer than 8. | Shorten the chain. |
| `haruhi:predicate <id> is not in the server's predicate registry; test is false` | A `haruhi:predicate` names a predicate the server's data packs lack. | Add the predicate, or fix the bundle. |
| `Haruhi storage closed before this operation ran` | A storage call was queued when storage closed at shutdown. | Nothing, unless it appears while the server is running. |
| Right-clicking an NPC does nothing | The NPC's profile is missing from the active content, has no `dialogue`, or no content is active. | `/haruhi content status`, then check the profile. |
| Using a bound block or entity does nothing, and its vanilla use does not happen either | The active content lacks the binding's graph. | `/haruhi bindings all` marks it `[missing graph]`; activate content that has the graph, or bind another one. |
| `<name>'s dialogue data is still loading; try again` | The player joined moments ago, or the load keeps failing. | Wait, then check the log for load errors. |
