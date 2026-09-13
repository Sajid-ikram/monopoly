# Monopoly

A multiplayer Monopoly for Android, built around an authoritative server and a
deterministic rules engine — because the common failure of digital Monopoly is
not the rules, it is the connection.

> **Status: early.** The rules engine, the game server and the board UI all
> work. The app plays a full hot-seat game on one device; it does not talk to
> the server yet.

## Why this exists

Most online Monopoly implementations fall over in the same two places:

1. **The connection.** A dropped signal ends your game, or silently leaves your
   board disagreeing with everyone else's.
2. **The rules.** Auctions are skipped, even-build is unenforced, the bank has
   infinite houses — small omissions that quietly change how the game plays.

This project treats both as correctness problems rather than polish.

## How it handles a bad connection

The whole design rests on one property: **the rules engine is a pure function.**

```
reduce(state, command) -> events        // decides what happened
apply(state, event)    -> state         // folds it in, with no rules knowledge
```

`reduce` performs no I/O, reads no clock, and draws no randomness except
through a seeded generator carried inside the game state itself. Given the same
state and the same command, it produces the same result on every machine.

That buys five things:

| Problem | How the design answers it |
| --- | --- |
| A client goes out of sync | Clients never compute the game. They fold the server's events with `apply`, which is provably the same function the server ran. |
| A player drops mid-turn | Their seat, cash and property are untouched. They present a resume token and get back the same seat. |
| A client missed some events | Every event has a gap-free sequence number. The client says how far it got; the server replays the gap or sends a snapshot. |
| A reply is lost in flight | Every command carries a client-generated id. Resending is free — the server recognises the id, does nothing twice, and returns the original result. |
| One player has a slow connection | Each socket has its own outbound queue, so broadcasting never blocks. A client that falls too far behind is resynced rather than allowed to stall the turn for everyone. |

A client can only ever send *intent*. The server decides, and the worst a bad or
hostile command achieves is a rejection — it cannot act as another player, and
it cannot make the board disagree with anyone else's.

The same properties make bugs tractable: a game is reproducible from its seed
and its event log, so a desync can be replayed rather than guessed at.

## Layout

```
core/       Pure Kotlin. Board data, game state, and the rules engine.
            No Android dependencies — the server reuses this verbatim,
            so both ends run byte-identical rules.
protocol/   The wire format: commands in, sequenced events out, plus the
            join/resume/snapshot messages that make reconnect work.
server/     Ktor WebSocket server. A referee around the engine: it decides
            whether and when a command runs, never what it does.
app/        The Android client (Jetpack Compose).
```

`core` and `protocol` are plain JVM modules, enforced by their build files
rather than by convention. The moment `core` gained an Android dependency the
server could no longer share it — and sharing it is the entire reason the two
ends can be trusted to agree.

## Rules implemented

Movement, GO salary, rent for streets, railroads and utilities, colour-group
rent doubling, houses and hotels with even-build enforcement and a finite bank
supply, mortgaging at half price and lifting at 10% interest, both card decks
with all 32 cards, jail in all four of its exits, property auctions, debt
settlement, and bankruptcy to either a player or the bank.

House rules are configuration, not forks — auctions, the Free Parking jackpot,
even-build, jail fine and turn limits, and starting cash are all settings that
travel with the game state, so a reconnecting client cannot disagree with the
host about what game it is in.

## Building

Requires JDK 17+ (Android Studio's bundled JBR works) and the Android SDK.

```bash
./gradlew :core:test :protocol:test :server:test   # all 103 tests
./gradlew :app:assembleDebug                       # the Android app
./gradlew :server:run                              # the game server on :8080
```

> Opening this in Android Studio for the first time after pulling? Run
> **File → Sync Project with Gradle Files**, or the IDE will not know the
> modules exist and Run will fail even though Gradle builds fine.

### Talking to the server

`GET /health` for uptime checks; everything else is one WebSocket at `/play`.
Create a game, and the server replies with a four-character code your friends
use to join:

```json
{"type":"com.monopoly.protocol.ClientMessage.CreateGame",
 "protocolVersion":1,"displayName":"Alice"}
```

## What's next

- [x] Deterministic rules engine with whole-game replay tests
- [x] Wire protocol with sequencing, resume and idempotent commands
- [x] Ktor WebSocket server with a per-game event log
- [x] Board UI and the full turn flow, playable hot-seat on one device
- [ ] Client session: connect, resume, sequence tracking, command retry
- [ ] Player-to-player trading
- [ ] Turn timers and a policy for a player who never reconnects
- [ ] Persist the event log so a server restart does not end games in progress

## Licence

Not yet chosen. Monopoly is a trademark of Hasbro; this is a personal project
and is not affiliated with or endorsed by them.
