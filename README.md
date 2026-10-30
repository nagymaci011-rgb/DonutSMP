# DonutCore

Donut SMP style survival core for Paper 26.2 (single plugin, no dependencies).

## Features
- Economy: /balance, /pay (1k, 2.5m, 1b ...), /baltop, /eco (admin), /shards
- /sell (GUI), /sell hand, /sell worth, prices in config.yml
- /ah auction house (GUI, sell, claim, expiry, cancel)
- /rtp, /spawn, /setspawn, /tpa, /tpaccept, /tpdeny with stand-still countdown
- Combat tag (blocks commands/teleport, kills combat loggers)
- Ranks: member, staff, admin, owner (/rank, chat prefix, /mute, /unmute)
- Sidebar scoreboard: money, shards, kills, deaths, playtime

## Build
Needs JDK 25 (Paper 26.1+ requires it) and network access to repo.papermc.io.

    gradle build

The jar is in build/libs/. Drop it into the server's plugins/ folder.
If the Paper API dependency does not resolve, set `paperApiVersion` in gradle.properties
to the exact version shown at https://docs.papermc.io (Paper API section).

Without a local setup: push this folder to a GitHub repo, the workflow in
.github/workflows/build.yml builds the jar and attaches it under Actions > Artifacts.

## First start
1. Put the jar in plugins/, start the server.
2. From the console: `rank <yourname> owner`
3. Join, stand at spawn, run `/setspawn`.
4. Edit plugins/DonutCore/config.yml (RTP radius, sell prices, rank prefixes), restart.
