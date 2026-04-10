# Datapack-Commands

A Fabric mod for Minecraft 1.20.5-26.1+ that lets datapack creators create custom slash commands that execute datapack functions. Commands are registered at runtime, persist across restarts, and show up in tab completion for all players — no server restart required.

---

## How to Use

All management is done through the `/cgen` command, which requires **operator level 4**.

### Creating a command

```
/cgen create <commandname> <namespace:function>
```
(Subcommands are also possible)

```
/cgen create "<commandname> <arg> <etc>" <namespace:function>
```

Registers a new `/<commandname>` that runs the specified datapack function when used. The command is available to all players immediately.
If using a subcommand make sure to add a " around the main command and subcommand

**Example:**
```
/cgen create ""spawn zombie"" example:spawn_zombie
```
Players can now run `/spawn zombie` and it will execute `example:spawn_zombie`.

---

### Removing a command

```
/cgen remove <commandname>
```

Unregisters the command immediately. Tab complete will suggest your registered commands after typing `/cgen remove `.

**Example:**
```
/cgen remove spawn
```

---

### Listing commands

```
/cgen list
```

Shows all currently registered commands and the functions they map to.

**Example output:**
```
Registered commands:
  /fasterpathsload -> fasterpaths:load
  /heal -> utils:heal_player
```

---

### Feedback toggle (default is off)

```
/cgen feedback on
/cgen feedback off
```

Controls whether the `"Executed function: ..."` message appears in chat when a player runs a custom command. Defaults to **off**. This setting persists across restarts.

---

### Check

```
/cgen check
```

Returns `1` if the mod is running correctly. Useful for datapacks that want to verify that the mod is loaded.

---

## Notes

- Commands are saved to `<world folder>/datapackcommands.json` and automatically re-registered on server start.
- The datapack function itself can still send its own chat messages (e.g. via `tellraw`) regardless of the feedback setting.
- Command names must be alphanumeric with underscores, up to 32 characters.
- Function names must follow the standard `namespace:path` format.
