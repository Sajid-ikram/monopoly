# Architecture

Notes on why the code is shaped the way it is. Read `README.md` first for the
short version.

## The one rule

**Game logic lives in `:core` and nowhere else.**

The client does not decide anything. It does not know that landing on Mayfair
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

## The server

`:server` is a referee, not a second implementation of Monopoly. It decides
*whether* a command may run and *when*; what it does is the engine's business.

**`GameSession`** owns one game. Every mutation runs under a single mutex, which
is what turns concurrent players into one total order — and that order is
exactly what the sequence numbers describe. Two players acting at the same
moment cannot interleave; one simply runs second and may find their command no
longer legal, which is the correct outcome rather than a race.

It holds four things: the authoritative `GameState`, the event log, a sequence
counter, and a map of command ids it has already answered.

That last one is what makes retrying safe. A client whose reply was lost resends
the same command id; the session returns the original answer rather than running
anything again. Without it a flaky connection could pay rent twice — and, just
as bad, a successful action could come back to the player as a confusing
rejection ("you already started the game").

**Identity is never taken from the message.** A command carries its actor for
the engine's benefit, but the session checks it against the socket's own
authenticated seat and rejects any mismatch. Possessing a resume token *is*
being that player, so tokens are 24 random bytes from a `SecureRandom` — a
guessable one would let a stranger take over a seat mid-game.

**Back-pressure is per-connection.** Each socket has its own bounded outbound
queue and its own writer coroutine, and broadcasting uses `trySend`, so it never
suspends. One player on a slow train cannot stall the turn for everyone else. If
a client's buffer fills, messages are dropped and the connection is flagged —
which is safe rather than lossy, because sequence numbers mean the client
notices the gap on the next message it receives and asks for a snapshot. Losing
a batch costs one round trip; blocking the game costs everybody.

**Seats outlive sockets.** Reattaching replaces an existing connection instead of
refusing it: a phone that lost signal leaves a socket the server has not yet
noticed is dead, and a returning player must not be locked out of their own game
waiting for a TCP timeout. Detaching only acts if the channel given is still the
live one, so a late close arriving after a reconnect cannot knock the new
connection offline.

Games live in memory, so a server restart loses those in flight. That is a
deliberate first step rather than an oversight: the event log is already the
right shape to persist, so durability is a matter of writing it somewhere rather
than restructuring anything.

## Module boundaries

```
:core      →  kotlinx-serialization only
:protocol  →  :core
:server    →  :core, :protocol, Ktor
:app       →  :core, :protocol, Android
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
- `LobbyTest` — joining, leaving and rule changes, which run through the same
  command/event pipeline as the game so that reconnecting during setup works
  identically to reconnecting mid-game.
- `WireTest` — round-trips every message, and confirms an event batch still
  replays correctly after a trip through JSON.
- `GameSessionTest` — the connection guarantees: that a retried command is not
  applied twice, that a rejected one consumes no sequence numbers, that a client
  cannot act as somebody else, that a small gap replays and a large one gets a
  snapshot, and that a seat survives its socket.
