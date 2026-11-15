# Unrabbit protocol

Single device, single server, one conversation stream. The debug-only HTTP transport uses JSON with `Authorization: Bearer <development credential>` and `X-Unrabbit-Server: <server UUID>`. The credential is private; examples intentionally omit it. Every endpoint requires both headers. Browser-origin requests are rejected; redirects are not followed by the app.

| Request | Result |
| --- | --- |
| `GET /v1/health` | Protocol version, server identity and selected agent |
| `POST /v1/jobs` | Persist an idempotent request; return its current state |
| `GET /v1/jobs/{id}` | Fetch the current state/result; unknown ID returns 404 |
| `POST /v1/jobs/{id}/cancel` with `{}` | Cancel queued/running work, or create a cancellation tombstone |

Submission shape:

```json
{
  "id": "ebd6ef29-ac52-4eb9-a18b-d065ae45f806",
  "text": "What do you notice?",
  "photos": []
}
```

A photo contains only `id` (UUID), `width`, `height` (1–2048) and `jpeg` (base64 bytes). Maximum four photos, 4 MiB each, and a 24 MiB JSON body. JPEG framing is checked; the service does not decode images or independently verify their declared dimensions. No client-selected paths, executable commands, working directories or credentials are accepted in the body. Text is limited to 16,000 characters; at most 32 unfinished jobs may be queued.

Responses contain `id`, `state`, `answer`, `error` and `photos` (metadata plus SHA-256, never the image body). States are `queued`, `running`, `completed`, `failed`, `cancelled`, `interrupted`. A repeated ID with identical normalized content returns its original job; changed content returns 409. A cancellation tombstone always stays cancelled.

The app persists a request before submitting it, first checks its ID, and uploads only if absent. Lost acknowledgements therefore do not create new work. Sleep stops client polling, not the worker. On wake, results are fetched by the original IDs. Service crashes mark running jobs interrupted; only untouched queued jobs resume.

## SSH pairing and requests

The VM runs its own AsyncSSH listener under the service account, separate from administrative OpenSSH. It uses a persistent P-256 host key in the private state directory. It provides no shell, PTY, SFTP/SCP, forwarding or arbitrary command execution.

The wizard starts this listener before creating the invitation. A version-2 `unrabbit.setup` QR contains `host`, `port`, `user`, `hostKeyFingerprint` (SHA-256), `serverId` (UUID), `pairingToken` (32 random bytes, URL-safe base64) and `expiresAt` (Unix seconds). The ten-minute code is a credential: never commit or log QR payloads. Only its digest is retained in the live registry; invitations do not survive a restart.

1. The app generates a P-256 device key. Its private bytes stay on the device, encrypted with an Android Keystore wrapping key and excluded from backup.
2. Before authenticating, it checks the SSH host key against the fingerprint in the scanned QR. There is no trust-on-first-use dialog or fallback to accepting an unknown key.
3. The code authenticates a temporary SSH session allowed only to execute `unrabbit-pair`. JSON on stdin contains exactly `serverId` and `publicKey`. Enrollment atomically records the public key and consumes the code. Already authenticated bootstrap sessions cannot reuse it.
4. The app reconnects using its private key and executes `unrabbit-api` with a health request. Only after server identity and key authentication succeed does it save the endpoint. A lost enrollment acknowledgement can be recovered using the already registered device key.
5. Normal requests use `unrabbit-api` with `{serverId, method, path, body}` on stdin, followed by EOF. Paths and bodies match the table above; GET uses a null body. The response is `{status, body}`. No OS command or filesystem path comes from the device.

SSH request bodies and responses are bounded. Requests have a 30-second receive deadline; the listener limits simultaneous connections and request handlers. The QR file is private and removed after verified pairing or clean shutdown. A restart retains the host identity, registered public keys, jobs and results but invalidates unconsumed invitations.

Client pause closes SSH and stops polling. Requests remain on the VM and results are collected by stable IDs on resume. Old local/demo messages are not uploaded. Changing VM is blocked while the app has unfinished requests.

Network failures keep requests pending with backoff. A rejected device key or malformed protocol response is terminal and releases the pending request so re-pairing is possible. SIGTERM closes the listener and stops the active agent process; its job remains interrupted and is not automatically replayed.

The HTTP development API must remain on loopback/inside its development container. Only the restricted SSH listener is intended to be reachable from the device. Automatic firewall changes, unattended service installation, multi-device history synchronization and semantic context compaction are outside this protocol.
