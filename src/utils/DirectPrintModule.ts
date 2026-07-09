import { NativeModules } from 'react-native';
import type {
  DirectPrinterConfig,
  PrintBlock,
  PrintSpec,
  PrintTextOptions,
  UsbDeviceInfo,
} from '../types/directPrinter';
import { StorageService } from './storage';

interface NativeConfig {
  connection: string;
  tcpHost: string;
  tcpPort: number;
  tcpTimeoutMs: number;
  usbVendorId: number;
  usbProductId: number;
  paperWidthDots: number;
  characterSet: string;
}

interface DirectPrintModuleType {
  printWebViewBitmap(config: NativeConfig): Promise<boolean>;
  printText(text: string, options: PrintTextOptions, config: NativeConfig): Promise<boolean>;
  printRaster(base64Bitmap: string, width: number, config: NativeConfig): Promise<boolean>;
  printBlocks(blocks: PrintBlock[], config: NativeConfig): Promise<boolean>;
  cutPaper(mode: 'full' | 'partial', config: NativeConfig): Promise<boolean>;
  openCashDrawer(pin: 2 | 5, config: NativeConfig): Promise<boolean>;
  testPrint(config: NativeConfig): Promise<boolean>;
  listUsbDevices(): Promise<UsbDeviceInfo[]>;
  requestUsbPermission(vendorId: number, productId: number): Promise<boolean>;
  pingTcpPrinter(host: string, port: number, timeoutMs: number): Promise<boolean>;
}

const NativeDirectPrint: DirectPrintModuleType = NativeModules.DirectPrintModule;

function toNativeConfig(cfg: DirectPrinterConfig): NativeConfig {
  // Coerce values to safe ranges so a stale/corrupt config can't reach the
  // native socket layer with garbage (port 0, negative timeout, etc.).
  const port = Number.isFinite(cfg.tcpPort) && cfg.tcpPort > 0 && cfg.tcpPort < 65536
    ? Math.trunc(cfg.tcpPort)
    : 9100;
  const timeout = Number.isFinite(cfg.tcpTimeoutMs) && cfg.tcpTimeoutMs >= 500
    ? Math.trunc(cfg.tcpTimeoutMs)
    : 5000;
  const widthDots = Number.isFinite(cfg.paperWidthDots) && cfg.paperWidthDots > 0
    ? Math.trunc(cfg.paperWidthDots)
    : 576;
  return {
    connection: cfg.connection === 'usb' ? 'usb' : 'tcp',
    tcpHost: (cfg.tcpHost ?? '').trim(),
    tcpPort: port,
    tcpTimeoutMs: timeout,
    usbVendorId: Math.trunc(cfg.usbVendorId || 0),
    usbProductId: Math.trunc(cfg.usbProductId || 0),
    paperWidthDots: widthDots,
    characterSet: cfg.characterSet || 'cp437',
  };
}

async function loadConfig(): Promise<DirectPrinterConfig> {
  return StorageService.getDirectPrinterConfig();
}

/**
 * Auto-cut tail applied after a print if the user enabled it. Reads the
 * latest config so a settings change takes effect on the next print.
 */
async function maybeAutoCut(cfg: DirectPrinterConfig): Promise<void> {
  if (!cfg.autoCut) return;
  try {
    await NativeDirectPrint.cutPaper(cfg.cutMode, toNativeConfig(cfg));
  } catch (err) {
    console.warn('[DirectPrint] auto-cut failed', err);
  }
}

/**
 * High-level API used by WebViewComponent and the settings UI. Each method
 * loads the current config from storage so the user doesn't have to wire it
 * through every call site.
 */
export const DirectPrint = {
  async printWebViewBitmap(): Promise<void> {
    const cfg = await loadConfig();
    await NativeDirectPrint.printWebViewBitmap(toNativeConfig(cfg));
    await maybeAutoCut(cfg);
  },

  async printText(text: string, options: PrintTextOptions = {}): Promise<void> {
    const cfg = await loadConfig();
    await NativeDirectPrint.printText(text, options, toNativeConfig(cfg));
  },

  async printRaster(base64Bitmap: string, width = 0): Promise<void> {
    const cfg = await loadConfig();
    await NativeDirectPrint.printRaster(base64Bitmap, width, toNativeConfig(cfg));
  },

  async printSpec(spec: PrintSpec): Promise<void> {
    const cfg = await loadConfig();
    const blocks: PrintBlock[] = [...(spec.blocks ?? [])];
    if (spec.cut) {
      blocks.push({ type: 'cut', mode: spec.cut === 'partial' ? 'partial' : 'full' });
    } else if (spec.cut === undefined && cfg.autoCut) {
      blocks.push({ type: 'cut', mode: cfg.cutMode });
    }
    if (spec.drawer) {
      const pin = typeof spec.drawer === 'object' ? spec.drawer.pin : 2;
      blocks.push({ type: 'drawer', pin });
    }
    await NativeDirectPrint.printBlocks(blocks, toNativeConfig(cfg));
  },

  async cutPaper(mode?: 'full' | 'partial'): Promise<void> {
    const cfg = await loadConfig();
    await NativeDirectPrint.cutPaper(mode ?? cfg.cutMode, toNativeConfig(cfg));
  },

  async openCashDrawer(pin: 2 | 5 = 2): Promise<void> {
    const cfg = await loadConfig();
    await NativeDirectPrint.openCashDrawer(pin, toNativeConfig(cfg));
  },

  async testPrint(): Promise<void> {
    const cfg = await loadConfig();
    await NativeDirectPrint.testPrint(toNativeConfig(cfg));
  },

  async testPrintWithConfig(cfg: DirectPrinterConfig): Promise<void> {
    // Used by Settings UI before persisting, so the user can test their draft
    // configuration without saving first.
    await NativeDirectPrint.testPrint(toNativeConfig(cfg));
  },

  listUsbDevices(): Promise<UsbDeviceInfo[]> {
    return NativeDirectPrint.listUsbDevices();
  },

  requestUsbPermission(vendorId: number, productId: number): Promise<boolean> {
    return NativeDirectPrint.requestUsbPermission(vendorId, productId);
  },

  pingTcpPrinter(host: string, port: number, timeoutMs = 3000): Promise<boolean> {
    return NativeDirectPrint.pingTcpPrinter(host, port, timeoutMs);
  },
};

export default DirectPrint;
