# Audit dimensions: what each reviewer is asked

Hand each reviewer its section verbatim, plus the repo-specific file list from the area map.
The questions below are the ones that produced real defects; keep them concrete and keep the
scenario-tracing form ("trace X end to end and say exactly what happens").

## Common output contract (append to every reviewer prompt)

Report: (1) defects with severity (critical/high/medium/low), `file:line`, what happens, the
cost scaling or the failing sequence, and a concrete fix; (2) an explicit list of what you
checked that is fine. Cite lines you actually read. No speculation beyond what the code shows.
Compact markdown.

## Performance

Split into area reviewers so each can read its files completely. Typical split for an
Android media app: data/cache/storage layer; list or grid UI plus view model; background
download pipeline; detail viewer plus metadata.

Ask every performance reviewer for:

1. **Work done more than once**: the same file decrypted or parsed twice (exists-then-read
   pairs), per-item filesystem stats or hashes inside loops, repeated directory listings,
   whole-document rewrites on single-item mutations, lists re-sorted or re-mapped on every
   emission, identity-keyed memos defeated by fresh copies, full copies to flip one flag.
2. **O(n²)**: `contains` on lists inside loops, full sorts to take k items, set differences
   built from lists, linear scans per touch event or per invalidation.
3. **The cold-start critical path**: everything that runs between process start and the first
   frame that shows cached content, including work that gates it indirectly (a session guard
   that withholds the active user until housekeeping ends, an eagerly evaluated initial state
   built on the main thread, dependency-graph construction on the main thread).
4. **Main-thread disk, crypto, decode, prefs, resource lookups per bind**.
5. **Allocation storms in hot paths**: per-bind strings and lambdas, `java.time` per item,
   boxed comparator keys, drawables rebuilt per render.
6. **Locks held across slow work**: mutex or shard lock around decrypt, decode, network,
   directory listing; single-threaded executors shared by cancellable and non-cancellable
   work; `cancel(true)` on work that cannot be interrupted.
7. **Decode waste**: decoding to check existence, decoding at a density the display cannot
   use, re-encoding what was delivered, orientation applied by copying, caches sized by count
   instead of bytes, cancelled loads that still decode.
8. **Write amplification**: anything persisted per progress callback; debounces bypassed by a
   forced flush on every step.

On a second pass, list what the first round fixed and ask specifically whether each fix
holds under the platform's real behaviour (example that bit: Android's GCM cipher buffers the
whole payload, so a Cipher stream is not streaming).

## Sync and reconciliation (data safety)

Trace these end to end and say exactly what is on disk and on screen afterwards:

1. An item leaves the remote listing while another listing still references it, while its
   rendition is mid-download, and while its original is mid-decrypt.
2. Process kill at each step of: listing commit; queue flush; original commit; item removal;
   user clear; secondary-listing reconcile. Does the next launch heal it? Can anything be
   deleted that should be kept? Are multi-file commits ordered write-new-before-delete-old?
3. Account switch A→B during: an in-flight sync for A; a batch for A; a debounced flush for
   A; a download for A. Can A's data land in B's directories or after A's directory was
   cleared? Can B observe A's in-memory state?
4. One file corrupt or unreadable (bad AEAD tag, truncated, zero-length, Keystore error):
   which files get deleted, is anything collateral, does re-enumeration recover it? Is a
   transient crypto or IO failure distinguished from proven corruption before deleting?
5. User mutations (trash, favourite) racing a periodic sync: can a sync's publish resurrect
   what the user removed or drop what they set? Check that mutations and syncs share a lock
   hierarchy, including secondary listings such as tags.
6. Concurrency: every mutex and every state-flow update; read-copy-write on `state.value`
   outside `update {}`; ordering between the primary and secondary mutexes; suspend calls
   inside `synchronized`.
7. Invariants the comments claim ("must", "never", "invariant"): are they still honoured?
8. Destructive reconciles with no sanity floor: can an early-ending enumeration erase the
   whole cache?

## Storage security and privacy (defensive review of the user's own app)

1. Cryptography: IV generation and uniqueness under concurrent writes; tag verification on
   every read path including streaming (can partial plaintext be observed before the tag is
   checked? does the platform cipher actually stream?); key wrapping spec flags; behaviour when
   a Keystore entry vanishes while its wrapped-key file remains; legacy-format upgrades; the
   database passphrase lifecycle and aliasing of the array; whether data can outlive its key or
   a key outlive its data (account switch, retain-only paths).
2. Plaintext exposure: where decrypted copies live, their permissions and TTL, whether any
   path hands a plaintext file or content URI across a process boundary, what the media player
   reads from, what a system cache clear does.
3. Logging: everything that reaches the log; can ids, names, URLs, sizes, offsets, or stack
   paths leak; does the sanitiser's own contract (length caps, `require` versus truncate) hold
   for every caller.
4. Manifest: exported components, backup and data-extraction rules, cleartext, permissions,
   foreground-service type, launch mode and task affinity of the launcher.
5. Update checks: fixed URL, redirects, which fields are read, can a hostile response drive a
   URL, a download, or a downgrade; unbounded server strings persisted before validation.
6. Any user- or server-controlled string that becomes a path, an alias, or an intent.

## Background worker: battery and network

1. Foreground-service lifetime: what starts it, what keeps it alive, the run cap, what ends a
   run early, whether it can be held with nothing to do (idle loops, admission waits without
   timeout, retry sleeps under the job wakelock), what the notification shows in each state.
2. Network: which work requires unmetered or validated networks and which does not; a network
   flip or airplane mode mid-batch; whether a run that ends for lack of network is ever
   re-scheduled; retry and backoff ladders per node and per run and whether they converge to a
   tight loop; SDK idle timeouts and re-asks.
3. Charger and battery rules: how "on screen" is determined and whether a glance authorises
   work that outlives it; anything that wakes or polls while idle.
4. Scheduler: constraints, unique-work names and policies (can two runs share one queue?),
   expedited or not, enqueue storms from every refresh or resume, `REPLACE` cancelling a
   running batch, a Cancel action that does not stick.
5. Periodic sync while in the app: cadence relative to freshness limits, what a tick costs
   when nothing changed, whether it runs in the background.
6. Wake locks, timers that run forever, foreground-info refreshes, deferred state lost on
   timeout or network loss, platform budgets for the declared service type.

## Process death and restoration

1. Process death at each screen and destination, relaunch from recents: what is restored,
   from where (saved state versus prefs versus in-memory), and what is lost or wrong. Note
   skeleton states and async first renders.
2. Process death inside the detail screen: what the intent carries, what an in-process source
   provides and what happens without it, whether index and id can mismatch, whether the wrong
   item can show or a null source crash, what happens to playing media, sheet state and zoom.
   Watch for state recorded by mutating the launch intent, which the system re-delivers
   unmodified after death.
3. Configuration changes (rotation, multi-window, dark mode, locale, timezone): what is
   recreated, what survives, leaked listeners, receivers, scopes, players and executors, work
   restarted needlessly, scroll and selection lost.
4. Recreation while background work continues: activations completing after destroy,
   deliveries to detached views, dialogs after `onSaveInstanceState`, raw dialogs that leak,
   mutations on a lifecycle scope that die between the server call and the UI update.
5. Account edge cases on restore: expired session, account removed while backgrounded,
   interrupted login, first launch with no account.
6. Intents: stale launches, duplicate launches, launch mode and affinity, back-stack.

## Test coverage gaps (a fixer, not a reviewer)

Find the classes that carry the most recently changed logic and have no direct tests
(typically the gateway/facade and the main view model). Have a fixer add JVM tests against
the real classes through the smallest possible seams (an interface for a platform-bound
collaborator, a constructor that takes a scope or dispatcher), covering ordering guarantees
(what publishes before what), account switch and disconnect ordering, cancellation
propagation, initial-state cheapness, and sharing/subscription behaviour.
