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


## Durability

The event log was always the authoritative account of a game, so persisting it
is a matter of writing it down rather than designing anything: one JSON Lines
file per game, appended and flushed as events happen.

Two things go in the file that are not events. The seed, because the opening
state is derived from it; and each seat's resume token, because a token is a
server-side secret that deliberately never appears in the stream clients see.
Without them the events would rebuild a game nobody could get back into.

A record is written **before** it is broadcast, so there is no window in which a
player has seen something the server would forget on a restart. A half-written
final line — what a process killed mid-append leaves behind — costs that one
line and not the file.

What this does not do is remember which commands have already been answered. A
client that retries an unacknowledged command across a restart will have it run
again. That is safe in practice rather than by construction: every command names
its actor and every phase names whose input it is waiting for, so a repeat
arrives to find the game has moved past it and is rejected.

## The player who never comes back

A seat nobody is sitting in must not be able to stop the game. After a minute
with no socket attached, the server plays one move at a time for whoever the
game is waiting on.

Only a *disconnected* player is ever played for. Someone looking at the board
and thinking is not holding the game up in a way software should fix, and taking
their turn from under them would be worse than waiting. That makes the rule easy
to state: the game moves on only when there is nobody there to move it.

Every stand-in move is the one that costs the absent player least — decline
rather than buy, fold rather than bid, refuse rather than accept. They should
come back to a game that carried on without them, not to one that spent their
money on their behalf.

The exception is a debt, because the game cannot continue until it is settled
and somebody has to choose what to give up. The order is the one a player would
use: cash, then buildings, then mortgages, and bankruptcy only when there is
genuinely nothing left — which is also the only point at which the engine
accepts it.

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


## The client

The client is the other half of the reconnect story, and it is deliberately
thin: it holds no rules, applies no optimistic updates, and cannot make the
board show anything the server did not send.

Three pieces:

**`GameConnection`** owns the socket and nothing else. It reopens it as often
as it takes, with capped exponential backoff and jitter — jitter because a whole
game's worth of clients drop at the same instant when the server restarts, and
without it they would all come back in the same instant too. It knows nothing
about Monopoly; what to say on a new socket is asked of its owner.

**`NetworkGame`** tracks the sequence it has reached and folds events onto the
state. The gap check is the load-bearing part: events only mean anything applied
to the state they were computed against, so a batch that does not follow on is
not something to apply carefully, it is something to refuse and replace with a
snapshot.

**`SessionStore`** keeps the resume token on disk. That token is the difference
between "my train went through a tunnel" and "I lost the game", so it is written
the moment the server issues it.

What falls out of this:

- Commands sent while the socket is down are queued, not lost, and delivered
  when it comes back.
- Commands sent but never acknowledged are resent on reconnect. Safe because
  each carries a client-generated id and the server performs a repeated id no
  second time — so the client can retry without ever having to work out whether
  the first attempt got through.
- Closing the app does not leave the game. The seat and the token outlive it,
  and the next launch offers to walk straight back in.

The same board renders a hot-seat game and a networked one, because both are a
`GameHolder`. The only thing the screen asks is whether this device may act for
a given seat — `null` meaning "all of them", which is what passing one phone
round the table actually means. The server does not trust that answer; it checks
the same thing itself against the seat the socket authenticated as.

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
- `RematchTest` — dealing again with the same people, including that the host
  keeps the role across the shuffle that starts a game. That last one is not
  hypothetical: the client assumed "host = first seat" and was wrong the moment
  play began.
- `PersistenceTest` — a game survives a restart with its sequence, its seats and
  its resume tokens; a corrupt file is skipped rather than fatal.
- `AbsentPlayerTest` — that an absent player is given time, is then played for,
  is never made to buy or accept anything, and that a connected player is never
  played for however long they take.

A note on the test suite itself: a Kotlin test whose last expression returns a
value (`assertIs`, `assertNotNull`) compiles to a non-void method, which JUnit
silently does not discover. Three tests had been passing that way — which is to
say not running at all — until the counts were compared against the declared
`@Test` methods. Every coroutine test is now `runBlocking<Unit>` for that
reason, and the declared-versus-executed count is worth checking in CI.
