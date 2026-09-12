# Monopoly

A multiplayer Monopoly for Android, built around an authoritative server and a
deterministic rules engine — because the common failure of digital Monopoly is
not the rules, it is the connection.

> **Status: early.** The rules engine is written and tested. There is no board
> UI and no server yet. The app currently launches to a placeholder screen.

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

That buys four things:

| Problem | How the design answers it |
| --- | --- |
| A client goes out of sync | Clients never compute the game. They fold the server's events with `apply`, which is provably the same function the server ran. |
| A player drops mid-turn | Their seat, cash and property are untouched. They present a resume token and get back the same seat. |
| A client missed some events | Every event has a gap-free sequence number. The client says how far it got; the server replays the gap or sends a snapshot. |
| A reply is lost in flight | Every command carries a client-generated id. Resending is free — the server recognises the id, does nothing twice, and returns the original result. |
| A client tries to cheat | It can't. A client only ever sends intent. The server decides, and the worst a bad command achieves is a rejection. |

The same properties make bugs tractable: a game is reproducible from its seed
and its event log, so a desync can be replayed rather than guessed at.

## Layout

```
core/       Pure Kotlin. Board data, game state, and the rules engine.
            No Android dependencies — the server will reuse this verbatim,
            so both ends run byte-identical rules.
protocol/   The wire format: commands in, sequenced events out, plus the
            join/resume/snapshot messages that make reconnect work.
app/        The Android client (Jetpack Compose).
```

`core` and `protocol` are plain JVM modules on purpose. When the server is
added it depends on exactly these two, and neither can accidentally acquire an
Android dependency that would prevent that.

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

Requires JDK 11+ (Android Studio's bundled JBR works) and the Android SDK.

```bash
./gradlew :core:test :protocol:test   # the rules engine and wire format
./gradlew :app:assembleDebug          # the Android app
./gradlew build                       # everything
```

## What's next

- [ ] Ktor WebSocket server wrapping `GameEngine`, with an event log per game
- [ ] Client session: join, resume, sequence tracking, command retry
- [ ] Board UI, and the turn flow on top of it
- [ ] Player-to-player trading
- [ ] Turn timers and a policy for a player who never reconnects

## Licence

Not yet chosen. Monopoly is a trademark of Hasbro; this is a personal project
and is not affiliated with or endorsed by them.
