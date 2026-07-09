/**
 * FreeKiosk - Printer Tab
 * Direct ESC/POS printing configuration (TCP / USB), bypassing Android PrintManager.
 */

import React, { useCallback, useEffect, useState } from 'react';
import { View, Text, StyleSheet, Alert, Modal, TouchableOpacity, FlatList } from 'react-native';
import {
  SettingsSection,
  SettingsSwitch,
  SettingsInput,
  SettingsRadioGroup,
  SettingsButton,
  SettingsInfoBox,
} from '../../../components/settings';
import { Colors, Spacing, Typography } from '../../../theme';
import DirectPrint from '../../../utils/DirectPrintModule';
import { StorageService } from '../../../utils/storage';
import type {
  DirectPrinterConfig,
  DirectPrinterConnection,
  DirectPrinterCharset,
  DirectPrinterCutMode,
  UsbDeviceInfo,
} from '../../../types/directPrinter';
import { DEFAULT_DIRECT_PRINTER_CONFIG } from '../../../types/directPrinter';

interface PrinterTabProps {
  // Browser printing (window.print)
  printEnabled: boolean;
  onPrintEnabledChange: (value: boolean) => void;
  printPaperSize: string;
  onPrintPaperSizeChange: (value: string) => void;
}

const PrinterTab: React.FC<PrinterTabProps> = ({
  printEnabled,
  onPrintEnabledChange,
  printPaperSize,
  onPrintPaperSizeChange,
}) => {
  const [config, setConfig] = useState<DirectPrinterConfig>(DEFAULT_DIRECT_PRINTER_CONFIG);
  const [tcpPortText, setTcpPortText] = useState<string>('9100');
  const [tcpTimeoutText, setTcpTimeoutText] = useState<string>('5000');
  const [testing, setTesting] = useState(false);
  const [pinging, setPinging] = useState(false);
  const [scanningUsb, setScanningUsb] = useState(false);
  const [showUsbPicker, setShowUsbPicker] = useState(false);
  const [usbDevices, setUsbDevices] = useState<UsbDeviceInfo[]>([]);

  // Load config on mount
  useEffect(() => {
    let cancelled = false;
    StorageService.getDirectPrinterConfig().then((cfg) => {
      if (cancelled) return;
      setConfig(cfg);
      setTcpPortText(String(cfg.tcpPort));
      setTcpTimeoutText(String(cfg.tcpTimeoutMs));
    });
    return () => { cancelled = true; };
  }, []);

  const update = useCallback((partial: Partial<DirectPrinterConfig>) => {
    setConfig((prev) => ({ ...prev, ...partial }));
  }, []);

  // Save individual fields as the user changes them. The aggregated config is
  // re-read from storage on every direct-print call so unsaved-but-applied
  // edits never silently desynchronize.
  const persist = useCallback(async (next: DirectPrinterConfig) => {
    await Promise.all([
      StorageService.saveDirectPrintEnabled(next.enabled),
      StorageService.saveDirectPrintConnection(next.connection),
      StorageService.saveDirectPrintTcpHost(next.tcpHost),
      StorageService.saveDirectPrintTcpPort(next.tcpPort),
      StorageService.saveDirectPrintTcpTimeout(next.tcpTimeoutMs),
      StorageService.saveDirectPrintUsbIds(next.usbVendorId, next.usbProductId),
      StorageService.saveDirectPrintPaperWidth(next.paperWidthDots),
      StorageService.saveDirectPrintAutoCut(next.autoCut),
      StorageService.saveDirectPrintCutMode(next.cutMode),
      StorageService.saveDirectPrintCharset(next.characterSet),
    ]);
  }, []);

  // Auto-save on any config change (debounced via React render cycle).
  useEffect(() => {
    persist(config).catch((err) => console.warn('[PrinterTab] persist failed', err));
  }, [config, persist]);

  const handlePortBlur = () => {
    const parsed = parseInt(tcpPortText, 10);
    if (Number.isFinite(parsed) && parsed > 0 && parsed < 65536) {
      update({ tcpPort: parsed });
    } else {
      setTcpPortText(String(config.tcpPort));
    }
  };

  const handleTimeoutBlur = () => {
    const parsed = parseInt(tcpTimeoutText, 10);
    if (Number.isFinite(parsed) && parsed >= 500 && parsed <= 60000) {
      update({ tcpTimeoutMs: parsed });
    } else {
      setTcpTimeoutText(String(config.tcpTimeoutMs));
    }
  };

  const onScanUsb = async () => {
    setScanningUsb(true);
    try {
      const devices = await DirectPrint.listUsbDevices();
      setUsbDevices(devices);
      setShowUsbPicker(true);
    } catch (err: any) {
      Alert.alert('USB scan failed', String(err?.message ?? err));
    } finally {
      setScanningUsb(false);
    }
  };

  const onSelectUsb = async (device: UsbDeviceInfo) => {
    setShowUsbPicker(false);
    update({ usbVendorId: device.vendorId, usbProductId: device.productId, connection: 'usb' });
    if (!device.hasPermission) {
      try {
        const granted = await DirectPrint.requestUsbPermission(device.vendorId, device.productId);
        if (!granted) {
          Alert.alert('USB permission', 'Permission was denied for this device.');
        }
      } catch (err: any) {
        Alert.alert('USB permission failed', String(err?.message ?? err));
      }
    }
  };

  const onPingTcp = async () => {
    if (!config.tcpHost) {
      Alert.alert('Ping', 'Enter a TCP host first.');
      return;
    }
    setPinging(true);
    try {
      const ok = await DirectPrint.pingTcpPrinter(config.tcpHost, config.tcpPort, 3000);
      Alert.alert('TCP ping', ok ? `Reached ${config.tcpHost}:${config.tcpPort}` : 'No response');
    } finally {
      setPinging(false);
    }
  };

  const onTestPrint = async () => {
    setTesting(true);
    try {
      await DirectPrint.testPrintWithConfig(config);
      Alert.alert('Test print', 'Test page sent to the printer.');
    } catch (err: any) {
      Alert.alert('Test print failed', String(err?.message ?? err));
    } finally {
      setTesting(false);
    }
  };

  return (
    <View>
      <SettingsSection title="Browser printing" icon="printer">
        <SettingsSwitch
          label="Allow Printing"
          hint="Enable window.print() support for web pages (label printers, receipts, etc.)"
          value={printEnabled}
          onValueChange={onPrintEnabledChange}
        />

        {printEnabled && (
          <>
            <View style={styles.rotationSpacer} />
            <SettingsRadioGroup
              label="Default Paper Size"
              options={[
                { value: 'A4',     label: 'A4 (210 × 297 mm)' },
                { value: 'A5',     label: 'A5 (148 × 210 mm)' },
                { value: 'A3',     label: 'A3 (297 × 420 mm)' },
                { value: 'LETTER', label: 'Letter (8.5 × 11 in)' },
                { value: 'LEGAL',  label: 'Legal (8.5 × 14 in)' },
              ]}
              value={printPaperSize}
              onValueChange={onPrintPaperSizeChange}
            />
          </>
        )}

        {printEnabled && (
          <SettingsInfoBox variant="info">
            {'🖨️ Web pages can trigger the Android print dialog via window.print().\n\n' +
              'In Device Owner (kiosk) mode, the system print spooler is automatically whitelisted to allow the print dialog to appear.\n\n' +
              'Supports WiFi, Bluetooth, USB printers, and Save as PDF.'}
          </SettingsInfoBox>
        )}
      </SettingsSection>

      <SettingsSection title="Direct printer" icon="printer">
        <SettingsSwitch
          label="Enable direct printing"
          hint="Bypass Android PrintManager — send ESC/POS directly to a thermal printer over TCP or USB. When off, window.print() falls back to the system print dialog."
          value={config.enabled}
          onValueChange={(v) => update({ enabled: v })}
        />
      </SettingsSection>

      {config.enabled && (
        <>
          <SettingsSection title="Connection">
            <SettingsRadioGroup
              label="Type"
              value={config.connection}
              onValueChange={(v) => update({ connection: v as DirectPrinterConnection })}
              options={[
                { value: 'tcp', label: 'TCP / Network', hint: 'Ethernet or Wi-Fi printer (RAW port 9100)' },
                { value: 'usb', label: 'USB', hint: 'Android USB Host — printer plugged into the device' },
              ]}
            />

            {config.connection === 'tcp' && (
              <>
                <SettingsInput
                  label="Host or IP address"
                  placeholder="192.168.1.100"
                  value={config.tcpHost}
                  onChangeText={(v) => update({ tcpHost: v.trim() })}
                  keyboardType="default"
                />
                <SettingsInput
                  label="Port"
                  placeholder="9100"
                  value={tcpPortText}
                  onChangeText={setTcpPortText}
                  onBlur={handlePortBlur}
                  keyboardType="numeric"
                  maxLength={5}
                />
                <SettingsInput
                  label="Connect timeout (ms)"
                  placeholder="5000"
                  value={tcpTimeoutText}
                  onChangeText={setTcpTimeoutText}
                  onBlur={handleTimeoutBlur}
                  keyboardType="numeric"
                  maxLength={5}
                />
                <SettingsButton
                  title={pinging ? 'Pinging…' : 'Ping printer'}
                  onPress={onPingTcp}
                  variant="outline"
                  disabled={pinging || !config.tcpHost}
                />
              </>
            )}

            {config.connection === 'usb' && (
              <>
                <View style={styles.kvRow}>
                  <Text style={styles.kvLabel}>Vendor ID</Text>
                  <Text style={styles.kvValue}>
                    {config.usbVendorId ? `0x${config.usbVendorId.toString(16).padStart(4, '0')}` : '—'}
                  </Text>
                </View>
                <View style={styles.kvRow}>
                  <Text style={styles.kvLabel}>Product ID</Text>
                  <Text style={styles.kvValue}>
                    {config.usbProductId ? `0x${config.usbProductId.toString(16).padStart(4, '0')}` : '—'}
                  </Text>
                </View>
                <SettingsButton
                  title={scanningUsb ? 'Scanning…' : 'Scan USB devices'}
                  onPress={onScanUsb}
                  variant="outline"
                  disabled={scanningUsb}
                />
                <SettingsInfoBox variant="info">
                  Connect the printer via USB before scanning. Android will prompt for permission the first time you select a device.
                </SettingsInfoBox>
              </>
            )}
          </SettingsSection>

          <SettingsSection title="Paper & encoding">
            <SettingsRadioGroup
              label="Paper width"
              value={String(config.paperWidthDots)}
              onValueChange={(v) => update({ paperWidthDots: parseInt(v, 10) })}
              options={[
                { value: '576', label: '80 mm', hint: '576 dots (TM-T88, most receipt printers)' },
                { value: '384', label: '58 mm', hint: '384 dots (compact thermal printers)' },
              ]}
            />
            <SettingsRadioGroup
              label="Character set"
              value={config.characterSet}
              onValueChange={(v) => update({ characterSet: v as DirectPrinterCharset })}
              options={[
                { value: 'cp437', label: 'CP437 (US)' },
                { value: 'cp850', label: 'CP850 (Latin-1)' },
                { value: 'cp858', label: 'CP858 (Latin-1 + €)' },
                { value: 'cp1252', label: 'Windows-1252' },
              ]}
            />
          </SettingsSection>

          <SettingsSection title="Cut">
            <SettingsSwitch
              label="Auto-cut after print"
              hint="Send a cut command after every print job."
              value={config.autoCut}
              onValueChange={(v) => update({ autoCut: v })}
            />
            {config.autoCut && (
              <SettingsRadioGroup
                label="Cut mode"
                value={config.cutMode}
                onValueChange={(v) => update({ cutMode: v as DirectPrinterCutMode })}
                options={[
                  { value: 'full', label: 'Full cut' },
                  { value: 'partial', label: 'Partial cut', hint: 'Leaves a small uncut bridge' },
                ]}
              />
            )}
          </SettingsSection>

          <SettingsSection title="Test">
            <SettingsButton
              title={testing ? 'Sending…' : 'Send test page'}
              icon="printer"
              onPress={onTestPrint}
              disabled={testing}
            />
            <SettingsInfoBox variant="info">
              Sends a standard ESC/POS test page (text alignment, font sizes, cut) using the current configuration. Use this to confirm the connection before deploying.
            </SettingsInfoBox>
          </SettingsSection>

          <SettingsSection title="JavaScript API" variant="info">
            <Text style={styles.body}>
              When direct printing is enabled, web pages can also call:
            </Text>
            <Text style={styles.code}>{`window.freekiosk.print({
  blocks: [
    { type: "text", text: "RECEIPT", bold: true, align: "center" },
    { type: "newline", count: 2 },
    { type: "text", text: "Total: $42.00" },
  ],
  cut: "full",
});`}</Text>
            <Text style={styles.body}>
              window.print() captures the WebView as a raster bitmap and sends it as-is.
            </Text>
          </SettingsSection>
        </>
      )}

      <Modal visible={showUsbPicker} animationType="slide" transparent onRequestClose={() => setShowUsbPicker(false)}>
        <View style={styles.modalBackdrop}>
          <View style={styles.modalContent}>
            <Text style={styles.modalTitle}>USB devices</Text>
            {usbDevices.length === 0 ? (
              <Text style={styles.modalEmpty}>No USB devices found. Plug the printer in and rescan.</Text>
            ) : (
              <FlatList
                data={usbDevices}
                keyExtractor={(item) => item.deviceName}
                renderItem={({ item }) => (
                  <TouchableOpacity style={styles.modalItem} onPress={() => onSelectUsb(item)}>
                    <Text style={styles.modalItemTitle}>
                      {item.productName ?? item.manufacturerName ?? item.deviceName}
                    </Text>
                    <Text style={styles.modalItemSub}>
                      VID 0x{item.vendorId.toString(16).padStart(4, '0')} ·
                      PID 0x{item.productId.toString(16).padStart(4, '0')}
                      {item.hasPermission ? '' : ' · permission required'}
                    </Text>
                  </TouchableOpacity>
                )}
              />
            )}
            <View style={styles.modalActions}>
              <SettingsButton title="Close" variant="outline" onPress={() => setShowUsbPicker(false)} />
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
};

const styles = StyleSheet.create({
  rotationSpacer: {
    height: Spacing.md,
  },
  kvRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: Spacing.sm,
  },
  kvLabel: {
    ...Typography.label,
    color: Colors.textSecondary,
  },
  kvValue: {
    ...Typography.body,
    color: Colors.textPrimary,
    fontVariant: ['tabular-nums'],
  },
  body: {
    ...Typography.body,
    marginBottom: Spacing.sm,
  },
  code: {
    fontFamily: 'monospace',
    fontSize: 12,
    backgroundColor: Colors.surfaceVariant,
    color: Colors.textPrimary,
    padding: Spacing.sm,
    borderRadius: Spacing.inputRadius,
    marginVertical: Spacing.sm,
  },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    padding: Spacing.md,
  },
  modalContent: {
    backgroundColor: Colors.surface,
    borderRadius: Spacing.cardRadius,
    padding: Spacing.cardPadding,
    maxHeight: '80%',
  },
  modalTitle: {
    ...Typography.label,
    fontSize: 18,
    marginBottom: Spacing.md,
  },
  modalEmpty: {
    ...Typography.body,
    color: Colors.textSecondary,
    textAlign: 'center',
    paddingVertical: Spacing.lg,
  },
  modalItem: {
    paddingVertical: Spacing.md,
    borderBottomWidth: 1,
    borderBottomColor: Colors.border,
  },
  modalItemTitle: {
    ...Typography.label,
  },
  modalItemSub: {
    ...Typography.hint,
    marginTop: Spacing.xs,
  },
  modalActions: {
    marginTop: Spacing.md,
  },
});

export default PrinterTab;
