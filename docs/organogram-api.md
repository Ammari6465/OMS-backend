# Organogram API

All routes are under the application's `/api` context and require a JWT.

## Read hierarchy

`GET /organogram?companyId=42&view=EMPLOYEE&includeVacancies=true`

- `view=EMPLOYEE` uses `staff.manager_id`.
- `view=POSITION` uses `positions.reports_to_position_id` and places open vacancies in that hierarchy.
- `SUPER_ADMIN` may request any active company. Other roles are restricted to the company in their JWT principal; a different `companyId` returns `403`.
- Chart nodes deliberately omit email and telephone fields. Authorized contact fields are available only from `GET /organogram/staff-details?staffId=...`.
- `rootIds`, `orphanIds`, and `warnings` let clients render malformed legacy data without losing nodes or recursing forever.

The response also contains active department metadata, optional vacancies, `dataVersion`, `generatedAt`, and the current user's `canEditHierarchy` and `canViewContactDetails` capabilities.

## Change employee manager

`PATCH /staff/{staffId}/manager`

```json
{"managerId": 123, "version": 7}
```

Only `SUPER_ADMIN` and company-scoped `COMPANY_ADMIN` users may call this route. `managerId` may be `null`. The service rejects self-management, descendant cycles, inactive/deleted managers, cross-company managers, and stale versions (`409`). A successful update writes a `REPARENT` audit record and publishes a company-scoped event after commit.

## Live stream

`GET /organogram/stream?companyId=42`, authenticated with the JWT `Authorization` header.

Events use the `organogram-change` SSE name:

```json
{"companyId":42,"entityType":"STAFF","entityId":9,"action":"REPARENT","version":8,"timestamp":"2026-08-18T07:00:00Z"}
```

Streams and STOMP topics are company-scoped. The frontend suppresses duplicate `(entityType, entityId, version)` events, debounces refresh bursts, and reconnects with exponential backoff.
