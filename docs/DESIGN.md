# Design

One conversation, one moving mark. Most interaction happens through the wheel and side button.

- Hold the side button, speak after listening starts and release to transcribe on the VM. The waiting animation and “Erkenne Sprache …” cover audio transfer and recognition; a short press or Back discards the pending transcript. Turning the wheel while holding discards the recording. Silence and recordings over 30 seconds produce a short notice without submitting a chat request or losing photo drafts. If the VM or speech service is unavailable, show a brief error and keep the drafts.
- Select the spoken language in welcome or settings: English by default, or German. Choosing a language returns to the originating screen. This affects recognition, not the current German interface.
- Planned reply audio: read answers aloud by default, with a persistent on/off setting; local synthesis or optional OpenAI with your own API key. Keep this independent of the spoken language. The proposed private API-key entry belongs in the VM wizard. Playback and provider setup are not implemented yet.
- Dark mode only: graphite `#171915`, warm text `#F2F0E8`, secondary text `#AFB4A8` and orange selection `#FF6A2A`.
- The logo stays behind the interface. It is bright at rest and while listening, quieter behind text, and changes shape with activity. No side glances or motion setting.
- Chat text scrolls freely. Messages are not selectable cards; there are no conversation titles, folders or new-chat controls.
- Battery, clock, Wi-Fi and VM connection move from a horizontal rest-screen row into a narrow vertical rail at the top left. Settings and network pages keep the same rail; cameras hide it. The VM glyph uses a check, pending dots or a slash, backed by an authenticated health check rather than saved pairing alone.
- Menus are vertical. Settings and network actions share borderless text rows, consistent spacing and an orange selection dot. There are no row dividers. Transitions are short crossfades, not separate screens full of instructions.
- Both cameras fill the display with a small touch-only X at the top right. The photo camera uses the side button as a shutter; the setup scanner recognizes QR codes automatically.
- “VM verbinden” opens the scanner directly from either welcome or settings. Connecting, permission errors and retries stay in that separate pairing flow; X/Back returns to its originating screen. Successful pairing opens chat.
- Setup offers Wi-Fi or optional SIM, spoken language and the VM wizard’s QR. Managed devices show an in-app Wi-Fi list and password field; additional network settings use an Android dialog. Network settings return with “Zurück”; VM pairing stays in its own menu action. SIM controls disappear when no card is present. VM pairing and verification are automatic; no manual VM connection fields, port wheel or save button.

IBM Plex Sans provides the type. The logo uses compatible paths for continuous morphing. Quiet motion is capped at roughly 12 updates per second, active states at roughly 30; hidden animation pauses and disabled system animations are respected.

The app and network setup have also been viewed on the r1 at 480 × 640 pixels, density 240. Full physical input, motion and energy checks are still in progress.
