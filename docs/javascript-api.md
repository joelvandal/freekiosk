# JavaScript API (`window.freekiosk`)

FreeKiosk injects a `window.freekiosk` namespace into the kiosk WebView so the page it displays can drive the device: print receipts, control audio, raise the soft keyboard, read the app version, trigger a self-update, or reboot the tablet.

Nothing here exists in a normal browser. Always feature-detect — and detect the **individual function**, not just the namespace, because older FreeKiosk builds expose only part of the surface.

```js
if (window.freekiosk && typeof window.freekiosk.checkUpdate === 'function') {
  // this build has the update API
}
```

| API | Available |
|-----|-----------|
| [`print()` / `cutPaper()` / `openCashDrawer()` (+ `Async`)](direct-printing.md) | only when **Settings → Printer → Enable direct printing** is on |
| [`audio.*`](#audio) | always |
| [`keyboard.*`](#soft-keyboard) | always |
| [`getVersion()` / `checkUpdate()` / `update()`](#version--self-update) | always |
| [`reboot()`](#device-reboot) | always (needs Device Owner to succeed) |

---

## Printing

The ESC/POS printing API — `print()`, `cutPaper()`, `openCashDrawer()`, their `Async` variants, the `onPrintResult` hook and the full block reference — is documented separately in **[Direct Printing](direct-printing.md)**.

---

## Audio

Control the media audio stream: output routing, volume, and mute. Every method returns a `Promise`.

### Output routing

```ts
window.freekiosk.audio.get(): Promise<{
  output: 'speaker' | 'jack' | 'both' | 'auto',
  forced: string | null,     // the value last passed to set(), or null
  headsetPlugged: boolean,   // a wired headset / jack is detected
}>

window.freekiosk.audio.set(
  mode: 'speaker' | 'jack' | 'both' | 'auto'
): Promise<{
  ok: boolean,
  output: string,
  privileged: boolean,       // true only if the system-level force succeeded
}>
```

```js
const info = await window.freekiosk.audio.get();
// { output: 'jack', forced: null, headsetPlugged: true }

await window.freekiosk.audio.set('speaker');
```

> [!WARNING]
> Forcing **media** audio to the speaker while a jack is plugged in requires the system-only `MODIFY_AUDIO_ROUTING` permission, which a normal app cannot hold. `set('speaker')` is therefore best-effort: `privileged` is usually `false`, and on many panels web-media audio still follows the jack. `'both'` is rarely supported by hardware. `get()`, volume and mute always work reliably.

### Volume & mute

```ts
window.freekiosk.audio.getVolume(): Promise<{
  volume: number,        // 0-100
  volumeRaw: number,     // raw stream value
  volumeMax: number,     // raw stream max
  isMuted: boolean,
  currentOutput: string,
}>

window.freekiosk.audio.setVolume(percent: number): Promise<{ ok: boolean }>  // 0-100, clamped
window.freekiosk.audio.setMuted(muted: boolean): Promise<{ ok: boolean }>
```

```js
const { volume, isMuted } = await window.freekiosk.audio.getVolume();
await window.freekiosk.audio.setVolume(75);
await window.freekiosk.audio.setMuted(true);
```

---

## Soft keyboard

Raise or dismiss the Android IME from the page. Useful on panels where the keyboard doesn't pop up on its own, and to get it out of the way once a form is done.

```ts
window.freekiosk.keyboard.show(): Promise<{ ok: true }>
window.freekiosk.keyboard.hide(): Promise<{ ok: true }>
```

Android only raises the IME for a **focused editable element**. `show()` calls `document.activeElement.focus()` first as a nudge, but the page must have focused the right field:

```js
const field = document.getElementById('search');

field.addEventListener('click', async () => {
  field.focus();                              // focus the target field first
  if (window.freekiosk && window.freekiosk.keyboard) {
    await window.freekiosk.keyboard.show();
  }
});

form.addEventListener('submit', () => {
  if (window.freekiosk && window.freekiosk.keyboard) {
    window.freekiosk.keyboard.hide();
  }
});
```

Both calls are best-effort: the promise resolving means FreeKiosk asked the system, not that the IME is on screen — a hardware keyboard or an OEM IME can ignore the request.

> [!TIP]
> When the keyboard covers the bottom of the page, FreeKiosk pads the WebView by the IME inset so form fields stay visible. Nothing to do from JS.

---

## Version & self-update

Read the installed version, check GitHub releases, and trigger an in-place update — the same mechanism as **Settings → About → Check for updates**.

```ts
window.freekiosk.getVersion(): Promise<{
  versionName: string,   // '1.2.27'
  versionCode: number,   // 49
}>

window.freekiosk.checkUpdate(opts?: { beta?: boolean }): Promise<{
  updateAvailable: boolean,   // semver compare; a stable release beats a pre-release of the same core
  current: string,            // '1.2.27'
  latest: string,             // '1.2.28'
  name: string,               // release title
  notes: string,              // release notes (markdown)
  publishedAt: string,        // ISO date
  downloadUrl: string,
  isPrerelease: boolean,
}>

window.freekiosk.update(opts?: { beta?: boolean }): Promise<{
  started: boolean,           // false when already up to date — nothing was downloaded
  /* ...plus every field of checkUpdate() */
}>
```

Pass `{ beta: true }` to include pre-releases in the channel. Unlike the print calls, these promises **reject** on error (no network, GitHub unreachable) — use `try/catch`.

```js
const { versionName } = await window.freekiosk.getVersion();
console.log('FreeKiosk', versionName);

try {
  const info = await window.freekiosk.checkUpdate();
  if (info.updateAvailable) {
    console.log('New version', info.latest, info.notes);
    const res = await window.freekiosk.update();
    if (res.started) {
      // APK downloading; Android's package installer takes over from here.
    }
  }
} catch (err) {
  console.error('Update check failed:', err.message);
}
```

`update()` downloads and installs **only when the release is genuinely newer** than the installed build; otherwise it resolves with `started: false` and does nothing — so it is safe to call unconditionally. Once `started` is `true` the download runs natively and Android shows its installer UI; the page keeps running until the app restarts.

> [!NOTE]
> This is a self-update from the FreeKiosk GitHub releases, not a fleet-management channel. On a Device-Owner kiosk the install still surfaces the system installer prompt unless the device policy grants silent installs.

---

## Device reboot

Reboots the tablet. Requires FreeKiosk to be **Device Owner**. Fire-and-forget — the device restarts, so there is nothing to await.

```ts
window.freekiosk.reboot(): void
```

```js
if (window.freekiosk && typeof window.freekiosk.reboot === 'function') {
  window.freekiosk.reboot();
}
```

> [!CAUTION]
> Every call reboots immediately. Guard it behind a confirmation (a two-step button, a PIN) so a stray tap can't restart the kiosk.

---

## Detection cheat sheet

```js
const fk = window.freekiosk || {};

const caps = {
  print:      typeof fk.print === 'function',            // direct printing enabled
  printAsync: typeof fk.printAsync === 'function',       // print status available
  audio:      !!fk.audio,
  volume:     !!(fk.audio && fk.audio.getVolume),
  keyboard:   !!fk.keyboard,
  update:     typeof fk.checkUpdate === 'function',
  reboot:     typeof fk.reboot === 'function',
};
```

A page that runs both in FreeKiosk and in a desktop browser should treat every one of these as optional and degrade silently when absent.

---

## See also

- [Direct Printing](direct-printing.md) — the full ESC/POS printing reference
- [REST API](rest-api.md) — control the device from outside, over HTTP
- [MQTT](MQTT.md) — telemetry and Home Assistant discovery
