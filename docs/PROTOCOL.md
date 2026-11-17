# Unrabbit protocol

Single device, single server, one conversation stream. The debug-only HTTP transport uses JSON with `Authorization: Bearer <development credential>` and `X-Unrabbit-Server: <server UUID>`. The credential is private; examples intentionally omit it. Every endpoint requires both headers. Browser-origin requests are rejected; redirects are not followed by the app.

| Request | Result |
| --- | --- |
| `GET /v1/health` | Protocol version, server identity, agent, recognition readiness and reply-voice readiness |
| `POST /v1/speech/transcribe` | Ephemeral audio recognition; returns text/language, creates no job |
| `POST /v1/speech/synthesize` | Ephemeral text-to-audio synthesis; returns compressed audio/language, creates no job |
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

Speech uses exactly `{"format":"aac-adts","language":"en","audio":"<base64>"}`; language is `en` or `de`. Audio must be AAC-LC, 16 kHz mono, with at most 256 KiB of compressed bytes and 30 seconds plus AAC padding. ADTS frame count and headers are checked before decoding. A successful response is `{"text":"recognized words","language":"en"}`; silence returns an empty string. Health includes `speech: {ready, model, languages}`. Missing speech setup returns 503, a busy recognizer 429, malformed audio 400 and excessive duration/size 413.

Speech requests use the same device authentication and server-identity checks as chat. They never persist audio, create an agent job or accept a path/URL. The app discards cancelled/late results and does not retry audio after a connection loss. Recognition already running on the VM may finish after cancellation; it cannot trigger a chat request. Only a subsequent ordinary text/photo job can invoke the agent. The client allows up to 45 seconds for a speech response.

Synthesis uses exactly `{"text":"Hello.","language":"en"}` with `en`/`de` and 1–240 characters. Blank text, control characters and additional fields are rejected. Success is `{"format":"aac-mp4","language":"en","audio":"<base64>"}`. Audio is mono AAC in MP4, at most 30 seconds and 256 KiB before base64, within the existing 512 KiB response limit. Health includes `tts: {ready, engine, voices}`. Missing setup returns 503, concurrent synthesis 429, malformed input 400, oversized input/output 413; other synthesis failures are sanitized. These requests use the same authentication and never enter the job/photo store. Text is treated as speech content, never a path or command. The app does not automatically retry failed/cancelled sections.

A photo contains `id` (UUID), `width`, `height` (1–2048), `jpeg` (base64 bytes) and optional `capturedAt` (positive Unix milliseconds). Current clients send the capture time; legacy clients without it age from server receipt. The server clamps future capture times to receipt time and rejects already-expired new uploads with 410. Maximum four photos, 4 MiB each, and a 24 MiB JSON body. JPEG framing is checked; the service does not decode images or independently verify their declared dimensions. No client-selected paths, executable commands, working directories or credentials are accepted in the body. Text is limited to 16,000 characters; at most 32 unfinished jobs may be queued.

Photo bytes expire after seven days. Cleanup preserves job IDs, original digests, image references and text/results; an idempotent retry cannot restore deleted bytes or renew expiry. Queued jobs whose required photos expire fail explicitly. The worker includes recent, unexpired photos from completed turns in subsequent model calls, reserving slots for current attachments within a four-image total. Earlier images are mapped to their original text turn; expired or omitted images are marked unavailable in model context. No re-upload is needed for follow-up questions. Update older servers before installing a client that sends `capturedAt`.

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
