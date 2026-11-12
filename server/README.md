# SiteXL Sync Server

A small, self-hosted sync API for SiteXL Keyboard. It stores a user's keyboard settings and a merged learned-word vocabulary. It does **not** host, download or manage standard language dictionaries; those remain managed by the Android app.

## Start with Docker

From the `server` directory, optionally copy `.env.example` to `.env` and edit it before starting the service:

```sh
cp .env.example .env
# Set ALLOW_REGISTRATION=true only when you want users to be able to create accounts.
docker compose up -d --build
```

`ALLOW_REGISTRATION` defaults to `false`. An unset value, an empty value, or anything other than `true` keeps public registration disabled. When disabled, the app displays the server message that registration is disabled. Set `ALLOW_REGISTRATION=true` in the Compose `.env` file to enable self-service account creation.

The API listens on port `8787` on the Docker host. Put it behind your existing reverse proxy and provide HTTPS. In Nginx Proxy Manager, forward the sync hostname to the Docker host on port `8787`, using HTTP between the proxy and this container; configure the public certificate on the proxy. Do not expose port 8787 directly to the internet when the reverse proxy is available.

Use a URL such as `https://sync.example.net` in the keyboard app (without an `/api` suffix).

## Accounts and sign-in

On first launch, SiteXL Keyboard asks for the server URL, username and password. Users can sign in or select **Register an account**. Registration is accepted only when `ALLOW_REGISTRATION=true`; otherwise the server returns HTTP 403 with `Registration is disabled on this server.` The app displays that message. The app stores the issued API token in Android Keystore-backed encrypted preferences; the account password is not stored.

Passwords are stored as PBKDF2-HMAC-SHA256 hashes with per-account random salts. The API issues an individual Bearer token at registration/sign-in. Signing out revokes the current token on the server when it is reachable and clears it from the device.

An administrator can still issue a token-only account from the running container:

```sh
docker compose exec sitexl-sync python -m app.main create-user ricardo
```

The command prints a long Bearer token once. This legacy administration flow does not create a password for interactive username/password sign-in. The server stores only a SHA-256 hash of each token.

To revoke a token:

```sh
docker compose exec sitexl-sync python -m app.main revoke-token 'PASTE_TOKEN_HERE'
```

There is no public registration endpoint. Accounts and tokens are created by the server administrator.

## Storage and backups

The named Docker volume `sitexl-sync-data` contains the SQLite database at `/data/sitexl-sync.sqlite3`. It stores:

- A versioned settings snapshot for each device belonging to the user.
- A merged vocabulary keyed by locale and word. When devices report the same word, the server keeps the highest frequency value and latest timestamp.

Back up the Docker volume regularly. The server stores settings snapshot ZIP files in the database. HTTPS protects data in transit; the database itself is not end-to-end encrypted, so protect the host and its backups accordingly.

## API

Interactive API documentation is available at `/docs` on the private service.

- `GET /health` — service health and registration-enabled status (no token required).
- `POST /api/v1/auth/register` — create an account when registration is enabled.
- `POST /api/v1/auth/login` — authenticate with username and password and issue a token.
- `POST /api/v1/auth/logout` — revoke the current Bearer token.
- `GET /api/v1/sync/settings` — list this user's device snapshots.
- `PUT /api/v1/sync/settings/{device_id}` — upload or update a settings snapshot.
- `GET /api/v1/sync/settings/{device_id}` — download a settings snapshot.
- `DELETE /api/v1/sync/settings/{device_id}` — delete a device settings snapshot.
- `PUT /api/v1/sync/vocabulary/{device_id}` — merge that device's learned words.
- `GET /api/v1/sync/vocabulary` — retrieve the merged vocabulary.

All `/api/v1/sync` routes require `Authorization: Bearer <token>`.

## Sync behaviour

- Learned words are merged across devices. The client sends individual dictionary entries only; typed sentences and clipboard history are not uploaded.
- Manually added Android personal-dictionary words, their shortcuts, frequencies and locale assignments are included in each settings snapshot.
- Keyboard settings use a latest-snapshot-wins policy. A device downloads a newer remote snapshot when it has no unsynchronised local settings changes. If two devices independently change settings before syncing, the device that uploads last becomes the latest settings snapshot.
- Server URL and API token are device-local connection credentials and are not included in the settings snapshot. Configure them on each new phone.
- Standard dictionaries and their selection/download flow are not modified by SiteXL Sync.
