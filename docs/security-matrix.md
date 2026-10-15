# Security matrix

Who may call what, as measured by the tests. Each cell is one real request through the real filter chain and method
security (`SecurityMatrixTest` in auth-service and event-service; tokens from the services' own keys or the test key).
Regenerate with `./mvnw verify`; the tables land in `services/*/target/security-matrix/*.md`. A new endpoint belongs in
its service's matrix test.

**Reading a cell:** ✅ means the response matched the rule. The number is the HTTP status. `401` / `403` are security
rejections (problem types `unauthorized` / `forbidden`). Any other status means the request got past authorization;
for example `✅ 401` on `/api/auth/refresh` is the business error `invalid-refresh-token` for a made-up token, and
`400` on `verify-email` is an invalid link. **Rule** is who the endpoint allows; anonymous callers of a non-public
endpoint get 401, logged-in callers without the right role get 403.

The gateway in front of both adds its own pre-check (no token or a bad token → 401 at the edge, public paths pass):
see `platform/api-gateway/.../EdgeSecurityTest`.

Last generated: 2026-10-16 (Phase 3), 253 / 253 cells as expected (auth-service 85, event-service 168).

### auth-service

| Endpoint | Rule | anonymous | student | volunteer | organizer | admin |
|---|---|---|---|---|---|---|
| `POST /api/auth/signup` | public | ✅ 201 | ✅ 201 | ✅ 201 | ✅ 201 | ✅ 201 |
| `GET /api/auth/verify-email?token=…` | public | ✅ 400 | ✅ 400 | ✅ 400 | ✅ 400 | ✅ 400 |
| `POST /api/auth/verify-email/resend` | public | ✅ 202 | ✅ 202 | ✅ 202 | ✅ 202 | ✅ 202 |
| `POST /api/auth/login` | public | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `POST /api/auth/refresh` | public | ✅ 401 | ✅ 401 | ✅ 401 | ✅ 401 | ✅ 401 |
| `POST /api/auth/logout` | public | ✅ 204 | ✅ 204 | ✅ 204 | ✅ 204 | ✅ 204 |
| `GET /.well-known/jwks.json` | public | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `GET /api/departments` | public | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `GET /api/users/me` | any logged-in user | ✅ 401 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `DELETE /api/users/me/sessions` | any logged-in user | ✅ 401 | ✅ 204 | ✅ 204 | ✅ 204 | ✅ 204 |
| `GET /api/users` | admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 403 | ✅ 200 |
| `GET /api/users/{own id}` | student, admin | ✅ 401 | ✅ 200 | ✅ 403 | ✅ 403 | ✅ 200 |
| `GET /api/users/lookup?email=…` | organizer, admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 200 | ✅ 200 |
| `PUT /api/users/{id}/roles` | admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 403 | ✅ 200 |
| `POST /api/users/{id}/unlock` | admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 403 | ✅ 204 |
| `DELETE /api/users/{id}/sessions` | admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 403 | ✅ 204 |
| `POST /api/auth/keys/rotate` | admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 403 | ✅ 201 |

85 / 85 cells as expected.

### event-service

| Endpoint | Rule | anonymous | student | volunteer | organizer (owner) | organizer (other) | admin |
|---|---|---|---|---|---|---|---|
| `GET /api/events` | public | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `GET /api/events/{id}` | public | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `POST /api/events` | organizer (owner), organizer (other), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 201 | ✅ 201 | ✅ 201 |
| `PUT /api/events/{id}` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 200 | ✅ 403 | ✅ 200 |
| `DELETE /api/events/{id}` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 409 | ✅ 403 | ✅ 409 |
| `GET /api/events/{id}/sessions` | public | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `POST /api/events/{id}/sessions` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 201 | ✅ 403 | ✅ 201 |
| `PUT /api/events/{id}/sessions/{sid}` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 200 | ✅ 403 | ✅ 200 |
| `DELETE /api/events/{id}/sessions/{sid}` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 204 | ✅ 403 | ✅ 204 |
| `GET /api/venues` | public | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `POST /api/venues` | organizer (owner), organizer (other), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 201 | ✅ 201 | ✅ 201 |
| `PUT /api/venues/{id}` | admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 403 | ✅ 403 | ✅ 200 |
| `GET /api/categories` | public | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `GET /api/events/{id}/gates` | any logged-in user | ✅ 401 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `POST /api/events/{id}/gates` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 201 | ✅ 403 | ✅ 201 |
| `DELETE /api/events/{id}/gates/{gid}` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 204 | ✅ 403 | ✅ 204 |
| `GET /api/events/{id}/volunteers` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 200 | ✅ 403 | ✅ 200 |
| `POST /api/events/{id}/volunteers` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 201 | ✅ 403 | ✅ 201 |
| `DELETE /api/events/{id}/volunteers/{vid}` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 204 | ✅ 403 | ✅ 204 |
| `GET /api/volunteers/me/assignments` | volunteer | ✅ 401 | ✅ 403 | ✅ 200 | ✅ 403 | ✅ 403 | ✅ 403 |
| `POST /api/events/{id}/transitions` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 200 | ✅ 403 | ✅ 200 |
| `GET /api/events/{id}/history` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 200 | ✅ 403 | ✅ 200 |
| `GET /api/events/{id}/registrations` | organizer (owner), admin | ✅ 401 | ✅ 403 | ✅ 403 | ✅ 200 | ✅ 403 | ✅ 200 |
| `GET /api/events/{id}/eligibility` | any logged-in user | ✅ 401 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `POST /api/registrations` | student, volunteer | ✅ 401 | ✅ 409 | ✅ 409 | ✅ 403 | ✅ 403 | ✅ 403 |
| `GET /api/registrations/me` | any logged-in user | ✅ 401 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 | ✅ 200 |
| `GET /api/registrations/{id}` | student, organizer (owner), admin | ✅ 401 | ✅ 200 | ✅ 403 | ✅ 200 | ✅ 403 | ✅ 200 |
| `POST /api/registrations/{id}/cancel` | student, organizer (owner), admin | ✅ 401 | ✅ 409 | ✅ 403 | ✅ 409 | ✅ 403 | ✅ 409 |

168 / 168 cells as expected.
