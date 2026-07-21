# Atlas — Inventory Service

> Authority for seat/room availability and reservation locks; enforces the no-oversell invariant.

Part of **[Atlas](https://github.com/atlas-event-lab)**. See the
[inventory diagrams](https://github.com/atlas-event-lab/atlas/tree/main/diagrams/inventory.md).

## Responsibilities

- Track flight seat availability and **per-night** hotel room availability (ADR-0008).
- Reserve/release capacity in reaction to the booking saga; never oversell.
- Emit both saga events (for Booking/Payment) and resource-availability events (for Search).

## Tech

Java 21 · Spring Boot · Spring Data JPA · PostgreSQL (`inventory_db`) · Kafka · Keycloak JWT.

## API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/inventory/flight/{flightId}` | Flight availability |
| GET | `/api/v1/inventory/hotel/{roomTypeId}` | Hotel room-type availability |

## Events

**Produces (saga, keyed by `bookingId`):** `inventory.reserved` (carries `amount`),
`inventory.rejected`, `inventory.released`.

**Produces (resource, keyed by `flightId`/`roomTypeId`, absolute `reserved` + `version`):**
`inventory.flight.reserved/released/expired`, `inventory.hotel.reserved/released/expired`.

**Consumes:** `booking.created/confirmed/cancelled/failed/expired`,
`flight.created/updated/deleted`, `hotel.created/updated/deleted` (seed catalog capacity).

## Model & state

`FlightInventory` (per flight) + `RoomTypeNightAvailability` (per room-type × night).
Reservation lifecycle: `RESERVED → CONFIRMED | RELEASED | EXPIRED`. A rolling scheduler keeps
the hotel night calendar materialized; an expiration scheduler releases stale holds.

## Data

Owns `inventory_db` (database-per-service).

## Patterns

Transactional outbox · idempotent consumers (`ConsumedEvent`) · reservation state guard ·
no-oversell domain metrics (ADR-0018) · idempotency metrics (ADR-0019) · availability resync
for read-model rebuild (ADR-0027).

## Run locally

```bash
docker compose up inventory-service
```

Env: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `KEYCLOAK_ISSUER_URI`.

## License

Apache-2.0 — see [`LICENSE`](./LICENSE).
