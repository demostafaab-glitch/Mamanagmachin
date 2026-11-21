# Rebuild plan — Mamanagmachin

## Product decision
- Android application, Arabic RTL, single local warehouse and fleet initially.
- Offline-first: all day-to-day operations must work with no network.
- Optional cloud synchronization is a later, explicitly enabled capability; local data remains authoritative until sync conflict rules are implemented.
- No stock pricing or accounting valuation.

## Repository audit (21 November 2026)
The previous implementation in this repository was a small Compose demo backed by SQLiteOpenHelper. It is not a complete fleet/warehouse product and must not be represented as one.

Open-source projects reviewed:
- `durmazoguzhan/StockManagementApp` — Apache-2.0 Android/Kotlin inventory sample, but its README and dependencies show Firebase Auth/Firestore/Storage. It is not offline-first and is not suitable to copy unchanged.
- `KeepISC/smart-car-service-book` — MIT-licensed React/TypeScript vehicle-garage and maintenance-history application. It depends on Firebase/Supabase services and is not an offline Android warehouse application. Use only as a domain reference after reviewing its maintenance logic; do not import its backend assumptions.
- `inventree/InvenTree` — MIT-licensed inventory system, but server-oriented and not a standalone offline Android app.

No single reviewed repository satisfies the required combination. Therefore, do not blindly fork or merge one and claim completion. Keep this repository as the Android product and use the reviewed projects only as references.

## Target technical architecture
- Kotlin, Android, Jetpack Compose, Material 3, Arabic RTL.
- Room database (SQLite) with explicit schema migrations; no network dependency for core workflows.
- Repository + ViewModel layers; calculation/business rules kept outside UI.
- WorkManager only for optional background backup/sync/reminders; local notifications for due maintenance.
- CSV/XLSX and PDF export; import/restore with validation and pre-restore backup.
- Automated unit tests for stock ledger, fuel consumption, odometer/hour-meter monotonicity, maintenance due calculations, and purchase receiving.

## Functional modules and invariants

### Inventory
- Items, categories, units, minimum stock, compatible machines, notes and attachments.
- Immutable stock movement ledger: receipt, issue, return, and authorized adjustment.
- On-hand quantity is derived from posted movements, never edited directly.
- Every issue/return references a machine or a documented reason.
- Purchase invoices do not change stock. Only a separately confirmed receiving document posts stock.

### Fleet
- Machine profile, identifiers, type, status, meter type and current meter reading.
- Meter readings are chronological; reject decreases unless an authorized correction is recorded with reason and audit entry.
- Drivers and assignment history.
- Fuel/oil records linked to machine, driver, date, quantity and meter reading.
- Consumption uses valid intervals and flags suspicious entries; it must not silently discard records.
- Maintenance schedules based on hours/km and/or calendar date; completed work records parts and stock issues.

### Controls
- Audit trail for create/edit/delete/post/reverse operations.
- Reversal/correction records instead of destructive deletion of posted transactions.
- Local backup/restore and attachment integrity checks.
- Arabic labels, dates and number formatting; units explicit on every quantity.

## Delivery gates
1. Confirm source structure and build reproducibly.
2. Implement Room schema and migrations with tests.
3. Implement inventory ledger and receiving workflow; test stock arithmetic.
4. Implement machine, driver and meter history.
5. Implement fuel/oil and maintenance calculations.
6. Add reports, backup/restore, audit log and optional sync.
7. Build APK only after CI tests pass; inspect artifact and installation package before sharing.

A green Gradle build alone does not mean feature acceptance. Each module requires functional tests and a device smoke test.
