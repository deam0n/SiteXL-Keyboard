# SiteXL Sync Server

A small, self-hosted sync API for SiteXL Keyboard. It stores a user's keyboard settings and a merged learned-word vocabulary. It does **not** host, download or manage standard language dictionaries; those remain managed by the Android app.

## Start with Docker

From the `server` directory:

```sh
docker compose up -d --build
```

The API listens on port `8787` on the Docker host. Put it behind your existing reverse proxy and provide HTTPS. In Nginx Proxy Manager, forward the sync hostname to the Docker host on port `8787`, using HTTP between the proxy and this container; configure the public certificate on the proxy. Do not expose port 8787 directly to the internet when the reverse proxy is available.

Use a URL such as `https://sync.example.net` in the keyboard app (without an `/api` suffix).

## Create an account token

Issue a token from the running container:

```sh
docker compose exec sitexl-sync python -m app.main create-user ricardo
```

The command prints a long Bearer token once. Copy it into **SiteXL Keyboard → SiteXL Sync**. Running the command again with the same user ID issues an additional token for that same account. The server stores only a SHA-256 hash of each token.

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

- `GET /health` — service health (no token required).
- `GET /api/v1/sync/settings` — list this user's device snapshots.
- `PUT /api/v1/sync/settings/{device_id}` — upload or update a settings snapshot.
- `GET /api/v1/sync/settings/{device_id}` — download a settings snapshot.
- `DELETE /api/v1/sync/settings/{device_id}` — delete a device settings snapshot.
- `PUT /api/v1/sync/vocabulary/{device_id}` — merge that device's learned words.
- `GET /api/v1/sync/vocabulary` — retrieve the merged vocabulary.

All `/api/v1/sync` routes require `Authorization: Bearer <token>`.

## Sync behaviour

- Learned words are merged across devices. The client sends individual dictionary entries only; typed sentences and clipboard history are not uploaded.
- Keyboard settings use a latest-snapshot-wins policy. A device downloads a newer remote snapshot when it has no unsynchronised local settings changes. If two devices independently change settings before syncing, the device that uploads last becomes the latest settings snapshot.
- Server URL and API token are device-local connection credentials and are not included in the settings snapshot. Configure them on each new phone.
- Standard dictionaries and their selection/download flow are not modified by SiteXL Sync.
