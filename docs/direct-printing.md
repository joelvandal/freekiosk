# Direct Printing (ESC/POS over TCP / USB)

FreeKiosk can send print jobs directly to a thermal receipt printer (Epson TM-T88, TM-T20, Bixolon, Star in ESC/POS mode, etc.) without going through Android PrintManager or a Print Service.

When **Settings → Printer → Enable direct printing** is on:

- `window.print()` captures the current WebView page and sends it as a raster bitmap.
- `window.freekiosk.print({...})` lets the web app send a structured ESC/POS document with text formatting, raster images, paper cut, and cash drawer control.

When direct printing is off, `window.print()` falls back to the standard Android print dialog (the previous behavior is unchanged).

---

## Configuration

In **Settings → Printer**:

| Field            | Description                                                          |
| ---------------- | -------------------------------------------------------------------- |
| Connection       | `TCP` (network, port 9100 RAW) or `USB` (Android USB Host)            |
| Host / Port      | TCP only — IP address of the printer and port (default `9100`)        |
| Vendor / Product | USB only — selected via the **Scan USB devices** picker               |
| Paper width      | `80 mm` (576 dots) or `58 mm` (384 dots)                              |
| Character set    | CP437, CP850, CP858 (with €), or Windows-1252                         |
| Auto-cut         | Send a cut command after every job (full or partial)                  |
| Test print       | Sends a built-in test page using the current configuration            |

---

## JavaScript API

The `window.freekiosk` namespace is injected into the WebView when direct printing is enabled. The rest of that namespace — audio, soft keyboard, version / self-update, reboot — is documented in the [JavaScript API reference](javascript-api.md).

Each print function comes in two flavors. The plain form (`print`, `cutPaper`, `openCashDrawer`) is fire-and-forget and returns `undefined`. The `Async` form (`printAsync`, `cutPaperAsync`, `openCashDrawerAsync`) takes the same arguments and returns a `Promise` that resolves to a [status object](#print-status). See [Reading the print status](#reading-the-print-status).

### `window.freekiosk.print(spec)`

Print a structured document. `spec` has the shape:

```ts
{
  blocks: PrintBlock[],
  cut?: 'full' | 'partial' | false,
  drawer?: boolean | { pin: 2 | 5 }
}
```

A `PrintBlock` is one of:

```ts
{ type: 'text', text: string, bold?: boolean, doubleHeight?: boolean, doubleWidth?: boolean, align?: 'left' | 'center' | 'right', font?: 'A' | 'B' }
{ type: 'newline', count?: number }
{ type: 'raster', base64: string, width?: number }   // PNG/JPEG payload, sized to printer width
{ type: 'cut', mode?: 'full' | 'partial' }
{ type: 'drawer', pin?: 2 | 5 }
{ type: 'barcode', symbology?: 'UPC_A' | 'UPC_E' | 'EAN13' | 'EAN8' | 'CODE39' | 'ITF' | 'CODABAR' | 'CODE93' | 'CODE128',
                   data: string, height?: number, width?: number,
                   hri?: 'none' | 'above' | 'below' | 'both',
                   align?: 'left' | 'center' | 'right' }
{ type: 'qrcode', data: string, moduleSize?: number, ec?: 'L' | 'M' | 'Q' | 'H',
                  align?: 'left' | 'center' | 'right' }
```

- If `cut` is omitted, the auto-cut setting from Settings is honored.
- If `cut: false` is passed explicitly, no cut is sent (use this to combine multiple jobs).
- If `drawer: true`, pin 2 fires after the print. Use `{ pin: 5 }` for the secondary drawer.

### `window.freekiosk.cutPaper(mode?)`

Send a single cut command. `mode` is `'full'` (default) or `'partial'`.

### `window.freekiosk.openCashDrawer(pin?)`

Open the cash drawer connected to the printer's DK port. `pin` is `2` (default) or `5`.

---

## Reading the print status

A print can fail for reasons the page can't see: the printer is off, the cable is out, the TCP host in Settings is wrong. There are two ways to find out, and they work together — pick whichever fits.

### `printAsync(spec)` / `cutPaperAsync(mode?)` / `openCashDrawerAsync(pin?)`

Same arguments as their plain counterparts, but they return a `Promise` resolving to a status object:

```js
const result = await window.freekiosk.printAsync({
  blocks: [{ type: 'text', text: 'Receipt' }],
  cut: 'full'
});

if (!result.ok) {
  showError('Printer: ' + result.message);
}
```

These promises **resolve** on failure rather than reject — check `result.ok`, don't wrap them in `try/catch`.

### `window.freekiosk.onPrintResult`

Assign a function here and it receives the status of **every** print job, including ones started with the plain fire-and-forget `print()`. This is the way to add error reporting to an existing page without touching its print calls:

```js
window.freekiosk.onPrintResult = function(result) {
  if (!result.ok) {
    console.error('[print]', result.code, result.message);
    showBanner('Printer error: ' + result.message);
  }
};

window.freekiosk.print({ blocks: [/* ... */] });   // unchanged, status still arrives above
```

If both are used, `printAsync()`'s promise and `onPrintResult` both fire for the same job. Correlate them with `requestId` if needed.

### Print status

```ts
{ ok: true,  requestId: string }
{ ok: false, requestId: string, code: string, message: string }
```

`ok: true` means the printer accepted the bytes. It is not a guarantee the paper came out — a thermal printer that's out of paper may still ack the job.

Common `code` values:

| Code | Meaning |
|------|---------|
| `INVALID_HOST`    | TCP host is empty — set it in Settings → Printer. |
| `INVALID_PORT`    | TCP port outside 1..65535 (default 9100). |
| `INVALID_USB_IDS` | No USB device selected — Settings → Printer → Scan USB devices. |
| `PRINT_FAILED`    | Transport failure: printer offline, connection refused, cable unplugged, USB permission missing. `message` carries the detail. |
| `BLOCKS_FAILED`   | The spec's blocks couldn't be encoded (bad base64 raster, bad barcode data). |
| `BRIDGE_ERROR`    | The call never left the page (serialization failure). |

Treat this list as indicative, not exhaustive — log `code` and `message` together.

---

## Examples

### 1. Minimal receipt

```js
window.freekiosk.print({
  blocks: [
    { type: 'text', text: 'COFFEE SHOP', bold: true, align: 'center', doubleHeight: true },
    { type: 'newline', count: 1 },
    { type: 'text', text: '123 Main St', align: 'center' },
    { type: 'text', text: 'Mon-Fri 7am-5pm', align: 'center' },
    { type: 'newline', count: 2 },
    { type: 'text', text: 'Espresso          3.00' },
    { type: 'text', text: 'Croissant         4.50' },
    { type: 'text', text: '------------------------' },
    { type: 'text', text: 'TOTAL             7.50', bold: true },
    { type: 'newline', count: 2 },
    { type: 'text', text: 'Thank you!', align: 'center' },
    { type: 'newline', count: 3 },
  ],
  cut: 'full',
});
```

### 2. Receipt with logo and cash drawer

The logo is a PNG encoded as base64. Width is in printer dots — `576` fits an 80 mm printer.

```js
window.freekiosk.print({
  blocks: [
    { type: 'raster', base64: 'iVBORw0KGgoAAAANSUhEUgAA...', width: 576 },
    { type: 'newline' },
    { type: 'text', text: 'Order #1042', align: 'center', bold: true },
    { type: 'newline', count: 2 },
    { type: 'text', text: '1x Burger           12.00' },
    { type: 'text', text: '1x Fries             4.50' },
    { type: 'text', text: '1x Soda              3.00' },
    { type: 'text', text: '------------------------' },
    { type: 'text', text: 'Subtotal            19.50' },
    { type: 'text', text: 'Tax  (5%)            0.98' },
    { type: 'text', text: 'TOTAL               20.48', doubleHeight: true, bold: true },
    { type: 'newline', count: 3 },
  ],
  cut: 'full',
  drawer: true,
});
```

### 3. Combining text alignment and sizes

```js
window.freekiosk.print({
  blocks: [
    { type: 'text', text: 'LEFT',   align: 'left' },
    { type: 'text', text: 'CENTER', align: 'center' },
    { type: 'text', text: 'RIGHT',  align: 'right' },
    { type: 'newline' },
    { type: 'text', text: 'Bold',         bold: true },
    { type: 'text', text: 'Double width', doubleWidth: true },
    { type: 'text', text: 'Double height', doubleHeight: true },
    { type: 'text', text: 'Big', doubleWidth: true, doubleHeight: true, bold: true, align: 'center' },
    { type: 'newline', count: 3 },
  ],
  cut: 'full',
});
```

### 4. Capture the current page (built-in `window.print`)

For any page that already looks the way you want it on screen, just call:

```js
window.print();
```

FreeKiosk will:

1. Capture the WebView as a bitmap.
2. Resize it to the configured paper width.
3. Apply Floyd-Steinberg dithering to convert to 1-bit monochrome.
4. Send the raster ESC/POS command(s) to the printer.
5. Cut the paper (if auto-cut is on).

This is the simplest path — no JS API changes required. Useful for label printing, page snapshots, or quick prototyping.

### 5. Open the cash drawer without printing

```js
window.freekiosk.openCashDrawer();        // pin 2
window.freekiosk.openCashDrawer(5);       // pin 5
```

### 6. Print without cutting (combine multiple jobs)

```js
// First half of a long receipt
window.freekiosk.print({
  blocks: [
    { type: 'text', text: 'Part 1', align: 'center' },
    // ...
  ],
  cut: false,
});

// Add more lines and finally cut
window.freekiosk.print({
  blocks: [
    { type: 'text', text: 'Part 2', align: 'center' },
    { type: 'newline', count: 3 },
  ],
  cut: 'full',
});
```

### 7. QR Code

A simple URL QR code, centered:

```js
window.freekiosk.print({
  blocks: [
    { type: 'text', text: 'Scan me', align: 'center', bold: true },
    { type: 'newline' },
    { type: 'qrcode', data: 'https://freekiosk.app', align: 'center' },
    { type: 'newline', count: 2 },
  ],
  cut: 'full',
});
```

Tuning `moduleSize` and error correction:

```js
// Big and robust — readable even when the receipt is folded or smudged.
{ type: 'qrcode', data: 'WIFI:T:WPA;S:Cafe-Free;P:welcome2024;;', moduleSize: 10, ec: 'H', align: 'center' }

// Smaller, default error correction (M ~15%)
{ type: 'qrcode', data: 'INV-2026-00042', moduleSize: 4, align: 'right' }
```

| `moduleSize` | Approx. width on 80 mm paper |
| ------------ | ----------------------------- |
| 3            | ~10 mm                        |
| 6 (default)  | ~20 mm                        |
| 10           | ~33 mm                        |
| 14           | ~46 mm                        |

`ec` controls Reed-Solomon error correction:

- `L` ~7% (max data capacity)
- `M` ~15% (default — recommended for clean receipts)
- `Q` ~25%
- `H` ~30% (most robust — survives partial smudges)

Use cases: Wi-Fi credentials, payment links, vCards, order tracking URLs, table reservations.

### 8. 1D Barcodes

The supported symbologies and what they expect:

| Symbology   | Use case                              | Data format                                   |
| ----------- | ------------------------------------- | --------------------------------------------- |
| `UPC_A`     | US retail products                    | 11 or 12 digits                               |
| `UPC_E`     | Compact UPC for small packages        | 6, 7, or 8 digits                             |
| `EAN13`     | International retail (≈ everywhere)   | 12 or 13 digits                               |
| `EAN8`      | Small retail items                    | 7 or 8 digits                                 |
| `CODE39`    | Industrial, logistics                 | Uppercase A–Z, 0–9, ` - . $ / + % SPACE`      |
| `CODE93`    | Denser CODE39 alternative             | Same set as CODE39 + lowercase via shift      |
| `CODE128`   | General-purpose, alphanumeric, dense (default) | Any ASCII; use `{A` `{B` `{C` to select sub-set |
| `ITF`       | Cartons, shipping (Interleaved 2/5)   | Even number of digits                         |
| `CODABAR`   | Blood banks, libraries, FedEx         | 0–9 plus `- $ : / . +`, start/stop char A–D   |

Examples:

```js
// Product label with EAN-13, centered, HRI digits below
window.freekiosk.print({
  blocks: [
    { type: 'text', text: 'Organic Coffee 1kg', align: 'center', bold: true },
    { type: 'newline' },
    {
      type: 'barcode',
      symbology: 'EAN13',
      data: '5901234123457',
      height: 100,
      width: 3,
      hri: 'below',
      align: 'center',
    },
    { type: 'newline', count: 2 },
  ],
  cut: 'full',
});
```

```js
// Shipping label with CODE128 — alphanumeric, very dense
window.freekiosk.print({
  blocks: [
    { type: 'text', text: 'SHIPMENT', align: 'center', doubleHeight: true, bold: true },
    { type: 'newline' },
    {
      type: 'barcode',
      symbology: 'CODE128',
      data: 'SHP-2026-AB-00042',
      height: 80,
      width: 2,
      hri: 'below',
      align: 'center',
    },
    { type: 'newline', count: 2 },
  ],
  cut: 'partial',
});
```

```js
// CODE39 — uppercase only, classic industrial label
{
  type: 'barcode',
  symbology: 'CODE39',
  data: 'INV2026-00042',
  hri: 'below',
  align: 'center',
}
```

```js
// ITF — even-length numeric, common on cartons
{
  type: 'barcode',
  symbology: 'ITF',
  data: '01234567890128',
  height: 80,
  hri: 'below',
}
```

```js
// UPC-A — US retail (always 12 digits including check digit)
{
  type: 'barcode',
  symbology: 'UPC_A',
  data: '012345678905',
  hri: 'below',
  align: 'center',
}
```

```js
// CODABAR — start/stop characters required (A/B/C/D, often A...A)
{
  type: 'barcode',
  symbology: 'CODABAR',
  data: 'A12345B',
  hri: 'below',
}
```

### 9. Combined receipt: text + logo + QR + barcode

A realistic point-of-sale ticket with everything:

```js
window.freekiosk.print({
  blocks: [
    { type: 'raster', base64: 'iVBORw0KGgoAAAANSUhEUgAA...', width: 576 },
    { type: 'newline' },
    { type: 'text', text: 'CAFÉ DU COIN',          align: 'center', bold: true, doubleHeight: true },
    { type: 'text', text: '123 rue Principale',    align: 'center' },
    { type: 'text', text: 'Tel: 514-555-1234',     align: 'center' },
    { type: 'newline' },
    { type: 'text', text: '--------------------------------' },
    { type: 'text', text: 'Order #1042 - 2026-05-19 14:32' },
    { type: 'newline' },
    { type: 'text', text: '2x Espresso          6.00' },
    { type: 'text', text: '1x Croissant         4.50' },
    { type: 'text', text: '1x Latte             5.50' },
    { type: 'text', text: '--------------------------------' },
    { type: 'text', text: 'Subtotal           16.00' },
    { type: 'text', text: 'TPS  (5%)           0.80' },
    { type: 'text', text: 'TVQ  (9.975%)       1.67' },
    { type: 'text', text: 'TOTAL              18.47', bold: true, doubleHeight: true },
    { type: 'newline', count: 2 },

    // Order number barcode for staff lookup
    { type: 'text', text: 'Order ID:', align: 'center' },
    {
      type: 'barcode',
      symbology: 'CODE128',
      data: 'ORD-1042',
      height: 60,
      width: 2,
      hri: 'below',
      align: 'center',
    },
    { type: 'newline' },

    // QR for the customer to leave a review
    { type: 'text', text: 'Leave a review:', align: 'center' },
    {
      type: 'qrcode',
      data: 'https://cafe-du-coin.example/review?o=1042',
      moduleSize: 6,
      ec: 'M',
      align: 'center',
    },
    { type: 'newline', count: 2 },

    { type: 'text', text: 'Merci !', align: 'center', bold: true },
    { type: 'newline', count: 3 },
  ],
  cut: 'full',
  drawer: true,
});
```

### 10. Detect availability before printing

`window.freekiosk` is only defined when direct printing is enabled in Settings. Web apps can detect this:

```js
if (window.freekiosk && typeof window.freekiosk.print === 'function') {
  window.freekiosk.print({ blocks: [/* ... */], cut: 'full' });
} else {
  // Fallback: use standard window.print()
  window.print();
}
```

Probe for `printAsync` specifically if you rely on the status object, since older FreeKiosk builds expose `print` but not `printAsync`:

```js
if (window.freekiosk && typeof window.freekiosk.printAsync === 'function') {
  const result = await window.freekiosk.printAsync({ blocks: [/* ... */], cut: 'full' });
  if (!result.ok) { showError(result.message); }
} else if (window.freekiosk) {
  window.freekiosk.print({ blocks: [/* ... */], cut: 'full' });  // no status available
}
```

---

## Tips

- **Image dimensions.** When using `{ type: 'raster' }`, encode the image at or below the paper width to avoid downsampling artifacts. 576 px wide for 80 mm, 384 px for 58 mm.
- **Character set.** Use CP858 if you need the Euro sign. Use CP1252 for most Western European accented characters. Test with a `testPrint` from Settings to confirm the printer matches.
- **Latency.** Each call opens a fresh TCP/USB session. To print many lines in one job, batch them into a single `freekiosk.print({ blocks: [...] })` call instead of looping over individual calls.
- **Errors.** Failures (printer offline, USB permission denied, host unreachable) are reported to the page — `await printAsync()` and check `result.ok`, or set a `window.freekiosk.onPrintResult` hook. See [Reading the print status](#reading-the-print-status). They are also logged natively as `[WebView] Direct print spec failed: …`.
- **USB permission.** The first time a USB printer is selected, Android prompts the user to allow access. Subsequent prints succeed silently until the device is unplugged.

---

## Supported printers

The implementation targets the generic ESC/POS dialect. Tested-or-known-compatible families:

- Epson TM-T20, TM-T70, TM-T88 (II–VII)
- Epson TM-m10, TM-m30
- Bixolon SRP-350, SRP-380
- Star TSP100, TSP650 — in ESC/POS emulation mode

Anything that accepts `ESC @` + raw text and supports `GS v 0` (raster bit image) and `GS V` (paper cut) should work.

---

## Troubleshooting

| Symptom                                  | Likely cause / fix                                                   |
| ---------------------------------------- | -------------------------------------------------------------------- |
| Test print does nothing, no error toast  | Wrong IP/port. Use **Ping printer** in Settings to verify the route. |
| Test print fails on USB                  | Permission not granted yet — pick the device once and accept the prompt. |
| `ENETUNREACH` from Android emulator      | The default AVD network (10.0.2.x) cannot reach LAN addresses like `192.168.x.x`. Test on a physical device on the same network, or use `10.0.2.2` to reach the host machine. |
| Connection error reports `(port 0)`      | Port stored as 0 / invalid. Reopen **Settings → Printer**, type the port (default 9100) and unfocus the field before saving. Recent builds reject invalid ports at save time and surface a clear `INVALID_PORT` error. |
| `INVALID_HOST` / `INVALID_PORT` toast    | The host or port field is empty / invalid. Configure them in Settings before running a test print. |
| `INVALID_USB_IDS`                        | No USB device was selected yet — use **Scan USB devices** in Settings. |
| `Connection refused` on a reachable IP   | The host is up but actively rejecting port 9100. Verify the printer truly listens on 9100 from the Android device's subnet (use **Ping printer** in Settings, or `Test-NetConnection 192.168.x.x -Port 9100` from a PC on the same network). On Epson TM-T88, check the built-in web UI: **TCP/IP → Port 9100 Enable**, and the **IP Filter / Access Control** list. Some Epson models ship in ePOS-Print-only mode and need RAW activated via EpsonNet Config or TM Utility. |
| Garbled accented characters              | Wrong character set. Try CP858 or Windows-1252. |
| Bitmap looks compressed vertically       | Paper width mismatch — set 58 mm (384) if your printer is 58 mm.      |
| `window.freekiosk` is `undefined`         | Direct printing not enabled in Settings, or printing master toggle is off. |
| Printer cuts but receipt is blank        | The page has white text or the WebView wasn't visible at capture time. Try `freekiosk.print({...})` with explicit text blocks. |
| Barcode prints but won't scan            | Invalid data for the symbology (EAN-13 needs 12–13 digits, ITF needs even-length numeric, CODABAR needs start/stop characters). |
| Barcode too narrow / unreadable          | Raise `height` (60–120 dots) and `width` (3–4 for module width). |
| QR code unreadable / data lost           | Raise `moduleSize` (try 8 or 10) and use `ec: 'Q'` or `'H'` for robustness. |
| QR code clipped on the side              | `moduleSize` is too large for the paper — try a smaller value or split the data. |
