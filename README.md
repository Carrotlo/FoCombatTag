![FoCombatTag cover](docs/assets/cover.png)

# FoCombatTag

[![Modrinth](https://img.shields.io/badge/Download-Modrinth-1bd96a?logo=modrinth&logoColor=white)](https://modrinth.com/plugin/focombattag) [![Versions](https://img.shields.io/badge/Supported_versions-Modrinth-1bd96a)](https://modrinth.com/plugin/focombattag/versions) [![Discord](https://img.shields.io/badge/Support-Discord-5865f2?logo=discord&logoColor=white)](https://discord.gg/kEF77QCfu7)

Combat tagging with command restrictions, safe-zone protection, and integrations.

*Developed by Carrotio.*

## Features

- Tags players after configured combat actions and shows the remaining time.
- Blocks configured commands and applies combat-logout consequences.
- Protects WorldGuard no-PvP regions and supports optional team handling.
- Stores restricted elytras safely for later claiming.
- Provides a public API, Bukkit lifecycle events, and PlaceholderAPI status values.

## Download and dependencies

Download the latest compatible version from [Modrinth](https://modrinth.com/plugin/focombattag). Check the [versions page](https://modrinth.com/plugin/focombattag/versions) for supported Minecraft versions and server platforms.

- **Required:** None
- **Optional integrations:** `WorldGuard`, `FoTeams`, `Skript`, `PlaceholderAPI`

## Screenshots

![Configure combat-tag behavior.](docs/assets/screenshots/focombattag-admin-editor.png)

*Configure combat-tag behavior.*

![Review WorldGuard border behavior.](docs/assets/screenshots/focombattag-worldguard-border.png)

*Review WorldGuard border behavior.*

## Support

For help or issue reports, join the [Carrotio Discord server](https://discord.gg/kEF77QCfu7).

<details>
<summary><strong>Commands and permissions</strong></summary>

### Commands

- `/focombattagadmin [version|reload|editor|tag|untag|status]` — FoCombatTag admin command Aliases: `/combattagadmin`.
- `/elytravault` — Claim elytras stored while combat tagged

### Permissions

- `focombattag.admin` — Access to FoCombatTag admin tools Default: `op`.

</details>

<details>
<summary><strong>PlaceholderAPI</strong></summary>

Requires PlaceholderAPI.

- `%focombattag_tagged%` — Whether the player is combat tagged.
- `%focombattag_remaining%` — Remaining tag time with an `s` suffix.
- `%focombattag_remaining_raw%` — Remaining tag time in seconds.

</details>

<details>
<summary><strong>Configuration</strong></summary>

- `config.yml` — Main behavior and feature settings.
- `messages.yml` — Player-facing messages and interface text.
- `sounds.yml` — Configurable interaction sounds.

Main config areas: `combat`, `actionbar`, `combat-logout`, `native-dialogs`, `commands`, `worldguard`, `file-logging`.

</details>

<details>
<summary><strong>Developer API</strong></summary>

FoCombatTag's Java API and events use `me.foesio.foCombatTag.api` and
`me.foesio.foCombatTag.api.event`. Compile against the matching FoCombatTag jar
as a provided/compile-only dependency. Do not shade it into your plugin.
Use `target/FoCombatTag-2.3.jar` from this checkout or a matching released jar.
No public Maven repository for this jar is declared in this project.

For Gradle:

```gradle
dependencies {
    compileOnly files('libs/FoCombatTag-2.3.jar')
}
```

For Maven, install the matching jar into your local repository, then depend on
it with `provided` scope:

```shell
mvn install:install-file -Dfile=FoCombatTag-2.3.jar -DgroupId=me.foesio -DartifactId=FoCombatTag -Dversion=2.3 -Dpackaging=jar
```

```xml
<dependency>
    <groupId>me.foesio</groupId>
    <artifactId>FoCombatTag</artifactId>
    <version>2.3</version>
    <scope>provided</scope>
</dependency>
```

For a Bukkit `plugin.yml` consumer, add `softdepend: [FoCombatTag]` when the
integration is optional, or `depend: [FoCombatTag]` when it is required. Check
that the provider is enabled before loading classes that directly reference
its API. Put optional integration code in a separate class loaded only after
that check. For a Paper `paper-plugin.yml` consumer, use:

```yaml
dependencies:
  server:
    FoCombatTag:
      load: BEFORE
      required: false
      join-classpath: true
```

`load: BEFORE` loads FoCombatTag before the consumer; set `required: true`
when the integration is mandatory.

```java
if (Bukkit.getPluginManager().isPluginEnabled("FoCombatTag")) {
    FoCombatTagApi.get().ifPresent(api -> {
        if (api.isTagged(player)) {
            long seconds = api.getRemainingSeconds(player);
        }
    });
}
```

The static API is installed during enable and removed during disable. Look it
up when needed; do not keep a reference across plugin reloads/disables. Its
`Player`-accepting methods and state-changing methods must run on the player's
server thread. Do not call the API asynchronously. Bukkit events are fired on
the thread that performs the operation; the current Bukkit/Paper gameplay paths
are synchronous. Regionized servers need separate validation before treating
these events as region-thread safe.

### Combat state

`isTagged(Player/UUID)` reports current effective in-memory state, not a
stored preference. `getRemainingSeconds(Player/UUID)` returns `0` without a
tag. `getActiveTags()` returns a list snapshot. `getTag(UUID)` returns an
`Optional<CombatTagDetails>` snapshot for one active tag, including opponent
UUID/name when known, source label for non-combat tags, expiry time in Unix
milliseconds, remaining seconds at the query time, and cause. An empty result
means no active tag. Tags are not persisted across restarts. API, admin, pearl,
and wind-charge tags have a source label but no opponent UUID or name.
Snapshots contain immutable values; later changes do not update old snapshots.

`tag(Player, sourceName, durationSeconds)` requests a tag and returns whether
that player's tag was applied, even if another listener immediately removes it
in the completed event. The duration is at least one second and may be
changed by `CombatTagApplyEvent`. Dead/offline players, disabled locations, or
cancelled events return `false` without a new tag. `tagCombat(attacker, victim)`
requests both tags and returns `true` if either succeeded; cancellation can
leave only one player tagged. `untag(Player)` returns `false` when no tag was
removed. These actions use the same rules as the plugin's own commands and
listeners.

`CombatTagApplyEvent` fires before every initial tag or refresh, after basic
player/location checks. It is cancellable and its duration is adjustable.
Cancellation leaves the previous tag intact. `CombatTagRemoveEvent` fires after
a tag is removed and before the usual untag notification, with a reason such
as expiry, death, quit, command, or API. It is not cancellable. An expired tag
with no online `Player` can disappear without a remove event. The apply event
is an attempt event: it does not prove a tag was committed.

`CombatTagCommitEvent` is the final cancellable gate after the apply event,
with its final duration, cause, opponent identity when known, and refresh flag.
It fires immediately before storage. Cancelling it leaves any previous tag
unchanged. `CombatTagAppliedEvent` fires only after the tag is stored and its
runtime work is scheduled, before notifications, elytra enforcement, and border
rendering. It is not cancellable and carries an immutable tag snapshot. A
listener can request removal during this event; later FoCombatTag side effects
are skipped if that happens. No committed event fires for rejected requests.

### Logout and commands

`CombatLogoutPenaltyEvent` fires on a tagged player's quit before FoCombatTag's
configured logout kill, broadcast, and console commands. Cancelling it skips
all three FoCombatTag penalties, so FoCombatTag will not kill the player and
cause death drops. The tag is still removed with reason `QUIT`. It cannot stop
penalties applied by other plugins or a death that already happened.

`CombatTaggedCommandBlockEvent` fires only after FoCombatTag decides a tagged
player's command is not on its configured allowlist. Its command string is the
original line, including slash and arguments. Cancelling this event lets the
command pass FoCombatTag's restriction; another plugin may still cancel the
Bukkit command event. It does not fire for allowed commands or untagged players.

### Elytra vault

`ElytraVaultStoreEvent` fires after a nonempty item is added to the in-memory
vault and a YAML save is attempted. It supplies a defensive item copy, total
stored stack count, and `isPersisted()` save result. It is not cancellable.

`ElytraVaultClaimEvent` fires after at least one stored item is transferred
to the player's inventory and a save of the remaining vault contents is
attempted. It reports the transferred item amount, remaining stack count, and
save result.
It is not cancellable and does not fire for an empty vault or zero transfer.
If a save fails, the operation has still changed runtime state; `isPersisted()`
is `false`, and the plugin logs the failure.

### Event example

```java
@EventHandler
public void onLogoutPenalty(CombatLogoutPenaltyEvent event) {
    if (myArena.hasNoLogoutPenalty(event.getPlayer())) {
        event.setCancelled(true);
    }
}

@EventHandler
public void onTagCommitted(CombatTagAppliedEvent event) {
    myScoreboard.refresh(event.getPlayer(), event.getTag().remainingSeconds());
}
```

### Complete public API method index

Signatures use short type names; import Fo types from this plugin's `api` package and Bukkit types from Bukkit.

#### `FoCombatTagApi`

```java
static Optional<FoCombatTagApi> get();
static boolean isAvailable();
static void install(me.foesio.foCombatTag.FoCombatTag);
static void uninstall();
boolean isTagged(Player);
boolean isTagged(UUID);
long getRemainingSeconds(Player);
long getRemainingSeconds(UUID);
boolean tag(Player);
boolean tag(Player, String);
boolean tag(Player, String, int);
boolean tagCombat(Player, Player);
boolean untag(Player);
List<CombatTagInfo> getActiveTags();
Optional<CombatTagDetails> getTag(UUID);
boolean isNoPvpRegion(Location);
me.foesio.foCombatTag.FoCombatTag plugin();
```

**Other public contract types:** `CombatTagCause`, `CombatTagDetails`, `CombatTagInfo`, `CombatTagRemoveReason`.

### Complete Bukkit event index

- `CombatLogoutPenaltyEvent`.
- `CombatTagAppliedEvent`.
- `CombatTagApplyEvent`.
- `CombatTagCommitEvent`.
- `CombatTagRemoveEvent`.
- `CombatTaggedCommandBlockEvent`.
- `ElytraVaultClaimEvent`.
- `ElytraVaultStoreEvent`.

</details>
