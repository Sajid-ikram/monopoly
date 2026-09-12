# Architecture

Notes on why the code is shaped the way it is. Read `README.md` first for the
short version.

## The one rule

**Game logic lives in `:core` and nowhere else.**

The client does not decide anything. It does not know that landing on Boardwalk
with a hotel costs $2000, that a mortgaged property earns nothing, or that the
third double sends you to jail. It renders state and sends intent.

This is not architectural purity for its own sake. Every rule duplicated in the
client is a rule that can drift from the server's copy, and a drifted rule
shows up as two players looking at different boards — the failure this project
exists to avoid.

## The three types

Everything flows through three shapes:

**`Command`** — what a player wants to do. `RollDice`, `BuyProperty`,
`PlaceBid`. Produced by the client, validated by the server, never trusted.
Every command names its actor explicitly rather than relying on whose turn it
is, so a command that arrives late is rejected rather than misattributed.

**`GameEvent`** — what actually happened. `PlayerMoved`, `MoneyTransferred`,
`DeedAssigned`. Produced only by the engine. Events are deliberately primitive
and carry absolute values, never instructions to recompute something.

**`GameState`** — the complete truth of a game. Complete is the operative word:
reconnect is implemented by shipping one of these, so anything held outside it
is state a returning player would silently lose.

```
Client                    Server                      Every client
  |                         |                              |
  |---- Command ----------->|                              |
  |                    reduce(state, cmd)                  |
  |                         |--- events ------------------>|
  |<--- CommandAccepted ----|                        apply(state, event)
```

## Why the engine is pure

`GameEngine.reduce` has no I/O, no clock, and no `kotlin.random.Random`.
Randomness comes from `Rng`, a SplitMix64 generator whose entire state is one
`Long` carried inside `GameState`.

The consequences are worth being explicit about:

- **A game is reproducible.** Seed plus command sequence determines everything.
  A desync can be replayed on a desk rather than theorised about.
- **The client can verify itself.** It folds the server's events and can compare
  its state version against the server's at any time.
- **Tests can be exhaustive.** `ReplayTest` plays 25 full games and asserts the
  replayed state equals the computed state, which would be impossible to state
  meaningfully if the engine could reach for a clock or a global RNG.

`applyEvent` carries a second obligation: it must be **total**. It never throws
on a well-formed event, even one naming a player it does not know. It runs
inside a client's replay loop, where throwing would turn a server-side bug into
a crashed game for everyone. Being wrong and recoverable beats being right and
dead.

If applying an event ever seems to need a dice roll or a legality check, the
event was under-specified. The fix belongs in `reduce`, never in `apply`.

## Why events rather than state diffs

The server could broadcast whole states. Events are better here because:

- They are small. A turn is a handful of events; a `GameState` is the entire board.
- They are ordered and gap-free, so a client can detect that it missed something
  rather than silently applying a stale update.
- They are a log. Persisted, they let the server rebuild any game — including
  after a restart mid-match.
- They drive the UI. "Bob paid you $450" is an event; deriving that sentence
  from two snapshots means diffing, which is harder and loses the reason.

Snapshots still exist, for a client so far behind that replay is not worth it,
and for joining a game in progress.

## Reconnection

Three mechanisms, each covering a different failure:

**Resume tokens.** Issued at join, stored on the device. A `PlayerId` is not a
connection id — a player keeps it across disconnects. Their seat and assets are
held; only their `connected` flag changes, and that is replicated so everyone
can see who has dropped.

**Sequence numbers.** Every event carries a gap-free `sequence`. A client
reports its high-water mark on reconnect and gets the difference. A batch whose
`fromSequence` does not line up tells the client immediately that it missed
something, instead of letting it apply events out of order.

**Command ids.** Every `Submit` carries a client-generated id. The server keeps
the ids it has seen, so a resend does nothing twice and returns the original
result. This is what makes retrying safe, and retrying safely is what makes a
flaky connection survivable rather than expensive.

## Decisions the engine deliberately does not make

When a player cannot pay, the game blocks in `AwaitingDebtSettlement` rather
than auto-selling their assets. Which house to give up is one of the few
genuinely interesting decisions in Monopoly. Auto-liquidation is convenient and
it is exactly the kind of shortcut that makes a digital adaptation feel unlike
the game.

Similarly, declining a property opens an auction by default. The official rules
have always said so; most implementations skip it, and skipping it noticeably
changes the economy of the game.

## Module boundaries

```
:core      →  kotlinx-serialization only
:protocol  →  :core
:app       →  :core, :protocol, Android
(:server)  →  :core, :protocol, Ktor          [not built yet]
```

`:core` and `:protocol` are plain JVM modules, not Android libraries, and this
is enforced by their build files rather than by convention. The moment `:core`
gains an Android dependency, the server can no longer share it — and sharing it
is the entire reason the client and server can be trusted to agree.

## Testing

- `BoardTest` — the board is transcribed data; a typo would produce a game that
  plays *almost* right, the worst kind of bug to find by playing.
- `RngTest` — determinism, range, and distribution of the generator everything
  else rests on.
- `RentTest` — rent checked against the printed title deeds.
- `GameEngineTest` — per-rule behaviour, including the rejections.
- `ReplayTest` — the load-bearing one. Plays whole games, then asserts that
  folding the event log reproduces the computed state exactly. Also checks that
  money is conserved except where the bank creates or destroys it.
- `WireTest` — round-trips every message, and confirms an event batch still
  replays correctly after a trip through JSON.
