# Accounts View — Reconcile Button

## Overview

Add a "Reconcile" button to each row in the AccountsView table. Clicking it calls `POST /api/v1/account/:id/reconcile` and shows an inline status badge in the same row indicating whether the call succeeded or failed. Status persists until the user navigates away.

## Architecture

Follows the same per-row status tracking pattern used in `IngestView`.

### Model changes (`AccountsView.scala`)

Add a `ReconcileStatus` enum:

```
Idle | InProgress | Success | Failed(msg: String)
```

Add a field to `Model`:

```scala
reconcileStatuses: Map[String, ReconcileStatus] = Map()
```

The key is the account's string ID.

### Message changes (`AccountsView.scala`)

Two new `Msg` cases:

- `ReconcileAccount(accountId: String)` — fired by button click
- `ReconcileResult(accountId: String, isSuccess: Boolean)` — fired by HTTP response

### Update logic (`AccountsView.scala`)

- `ReconcileAccount(id)` → set status to `InProgress`, emit `HttpHelper.reconcileAccount(id)`
- `ReconcileResult(id, true)` → set status to `Success`, `Cmd.None`
- `ReconcileResult(id, false)` → set status to `Failed("Error")`, `Cmd.None`

### HTTP layer (`HttpHelper.scala`)

New function:

```scala
def reconcileAccount(accountId: String): Cmd[Task, Msg.SendHttpRequestWithAccessToken]
```

Posts to `HttpPath.url(s"${HttpPath.account}/$accountId/reconcile")` with `Body.Empty`.

New decoder in `ResponseDecoders`:

```scala
def reconcileDecoder(accountId: String): Decoder[Msg]
```

- On success → `Msg.AccountsMsg(AccountsView.Msg.ReconcileResult(accountId, true))`
- On error → `Msg.AccountsMsg(AccountsView.Msg.ReconcileResult(accountId, false))`

### View changes (`AccountsView.scala`)

Table headers gain a fifth empty column (`""`).

Each row gains two new cells:

1. `Buttons.textButton("Reconcile", AppMsg.AccountsMsg(Msg.ReconcileAccount(account.id.get)))`
2. A status badge span — same styling as `IngestView.statusBadge`:
   - `Idle` → empty span
   - `InProgress` → blue "Running…"
   - `Success` → green "Done"
   - `Failed(msg)` → red `s"Error: $msg"`

## Scope

Backend endpoint already exists. No backend changes required.
