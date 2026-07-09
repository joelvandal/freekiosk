export type DirectPrinterConnection = 'tcp' | 'usb';
export type DirectPrinterCharset = 'cp437' | 'cp850' | 'cp858' | 'cp1252';
export type DirectPrinterCutMode = 'full' | 'partial';

export interface DirectPrinterConfig {
  enabled: boolean;
  connection: DirectPrinterConnection;
  tcpHost: string;
  tcpPort: number;
  tcpTimeoutMs: number;
  usbVendorId: number;
  usbProductId: number;
  paperWidthDots: number;
  autoCut: boolean;
  cutMode: DirectPrinterCutMode;
  characterSet: DirectPrinterCharset;
}

export const DEFAULT_DIRECT_PRINTER_CONFIG: DirectPrinterConfig = {
  enabled: false,
  connection: 'tcp',
  tcpHost: '',
  tcpPort: 9100,
  tcpTimeoutMs: 5000,
  usbVendorId: 0,
  usbProductId: 0,
  paperWidthDots: 576,
  autoCut: true,
  cutMode: 'full',
  characterSet: 'cp437',
};

export interface UsbDeviceInfo {
  deviceName: string;
  vendorId: number;
  productId: number;
  manufacturerName: string | null;
  productName: string | null;
  hasPermission: boolean;
}

export interface PrintTextOptions {
  bold?: boolean;
  doubleHeight?: boolean;
  doubleWidth?: boolean;
  align?: 'left' | 'center' | 'right';
  font?: 'A' | 'B';
}

export type BarcodeSymbology =
  | 'UPC_A'
  | 'UPC_E'
  | 'EAN13'
  | 'EAN8'
  | 'CODE39'
  | 'ITF'
  | 'CODABAR'
  | 'CODE93'
  | 'CODE128';

export type BarcodeHri = 'none' | 'above' | 'below' | 'both';

export type QrErrorCorrection = 'L' | 'M' | 'Q' | 'H';

export type PrintBlock =
  | {
      type: 'text';
      text: string;
      bold?: boolean;
      doubleHeight?: boolean;
      doubleWidth?: boolean;
      align?: 'left' | 'center' | 'right';
      font?: 'A' | 'B';
    }
  | { type: 'newline'; count?: number }
  | { type: 'raster'; base64: string; width?: number }
  | { type: 'cut'; mode?: DirectPrinterCutMode }
  | { type: 'drawer'; pin?: 2 | 5 }
  | {
      type: 'barcode';
      symbology?: BarcodeSymbology;
      data: string;
      height?: number; // dots, 1..255 (default 80)
      width?: number; // module width 2..6 (default 2)
      hri?: BarcodeHri;
      align?: 'left' | 'center' | 'right';
    }
  | {
      type: 'qrcode';
      data: string;
      moduleSize?: number; // 1..16 (default 6)
      ec?: QrErrorCorrection;
      align?: 'left' | 'center' | 'right';
    };

export interface PrintSpec {
  blocks: PrintBlock[];
  cut?: DirectPrinterCutMode | false;
  drawer?: boolean | { pin: 2 | 5 };
}
