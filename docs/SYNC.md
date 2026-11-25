# Shared household sync (design)

Optional. Personal mode has no sync, no sign-in and no network use.

## Roles
| Role | Can do |
|---|---|
| Admin (exactly one) | Everything. Adds members, approves or rejects requests. Owns the household data. |
| Reviewer | Proposes new, changed or deleted transactions and purchase requests. Nothing counts until the Admin approves. |
| Read-only | Views data and reports. |

## Where data lives
- Every phone keeps its data in its own local database.
- Phones exchange **encrypted** files through a Google Drive folder that the Admin creates and shares with members.
- The **Admin's phone is the source of truth**. Other phones rebuild their data from the Admin's events.

## Folder layout (a log in the style of Kafka)
| File | Written by | Contents |
|---|---|---|
| `household.json` | Admin, once | Household name, Admin id, key salt, KDF parameters, an encrypted passphrase check. No financial data. |
| `member-<id>.enc` | That member only | Name, role, head offset of their partition, month segments, offsets they have consumed from other partitions, last sync time. The Admin's file also holds the roster. |
| `log-<id>-<yyyy-MM>.enc` | That member only | Their partition: numbered events for one month. |
| `snapshot.enc` | Admin only | The full household state at an offset, so a new phone can start without replaying everything. |

**Why every file has exactly one writer:** Drive has no locking, so two phones writing one file can silently lose an update.

## Events
| Kind | Partition | Meaning |
|---|---|---|
| `upsert` / `delete` | Admin | A row of household data changed (accounts, categories, transactions, budgets, repeating rules, stock items, purchase requests, learned item groups). |
| `setting` | Admin | A household setting changed (currency). |
| `proposal` | Reviewer | "Please add, change or delete this transaction" or "Please buy this". |
| `decision` | Admin | A proposal was approved or rejected, with an optional reason. |

The Admin's phone builds its events by comparing the current data with what it last published. This catches every change: edits, restores and repeating transactions.

## Sync steps
1. **App start:** upload this phone's new events and its member file, then download **only the member files**. That is enough to show every member's lag.
2. **Sync now:** download the new events from the partitions this phone reads.
   - The Admin reads every Reviewer partition: new proposals go to the Review page.
   - Other phones read the Admin partition: data changes and decisions.
3. **Applying changes on a non-Admin phone:**
   - Admin events are applied to the shared state.
   - The result is restored in one database transaction, with the same validation as a backup restore.
   - The phone's own bills and requests are kept.

**Lag:** `behind` = the Admin's head offset minus the member's consumed Admin offset. Everyone can see the member list and each member's lag. The Admin follows up personally if someone is far behind.

## Encryption
- **Key:** PBKDF2-HMAC-SHA256 of the household passphrase with a random 16-byte salt and 210,000 iterations, giving a 256-bit key.
- **Each file:** AES-256-GCM with a fresh 12-byte IV. The file name is bound as associated data, so a renamed or swapped file fails to decrypt.
- **The passphrase:** never leaves the phone. Members share it in person.
- **Stored key:** the derived key is stored wrapped by an Android Keystore key.
- **Not encrypted:** file names and `household.json`, which holds no financial data, are visible to Google. So are file sizes and times.

## Trust
- Files are checked against the roster. A member's files must be owned by that member's Google account, and the Admin's files by the Admin.
- Anyone the Admin shares the folder with, and who knows the passphrase, is trusted: this is designed for a family.

## Not shared
- Bill photos and bill details stay on the phone that scanned them. A shared transaction loses its link to the bill.

## Google Drive permission
Reading files that another member wrote in a shared folder needs the full Drive permission. Google classes it as restricted, which means:
- every phone sees a "Google hasn't verified this app" screen once;
- at most 100 users;
- a Google security review would be needed before a Play Store release with sync.

The Drive code sits behind the `RemoteFolder` interface, so the transport can be replaced later.
