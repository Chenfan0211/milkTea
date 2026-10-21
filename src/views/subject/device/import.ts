export const DEVICE_IDENTITY_PATTERN = /^[A-Za-z0-9._-]{1,64}$/;

function hasControlChar(value: string): boolean {
  for (const char of value) {
    const code = char.codePointAt(0) ?? 0;
    if (code <= 0x1f || code === 0x7f) return true;
  }
  return false;
}

export interface DeviceImportParseResult {
  ok: Record<string, any>[];
  errors: string[];
}

export function parseDeviceImportRows(rows: Record<string, string>[]): DeviceImportParseResult {
  const errors: string[] = [];
  const ok: Record<string, any>[] = [];
  const seenDeviceSn = new Set<string>();
  const seenDeviceName = new Set<string>();

  rows.forEach((row, index) => {
    const line = index + 2;
    const deviceSn = String(row.deviceSn ?? '').trim();
    const deviceName = String(row.deviceName ?? '').trim();
    const alias = String(row.alias ?? '').trim();
    const remark = String(row.remark ?? '').trim();
    const enabledText = String(row.enabled ?? '').trim();
    const rowErrors: string[] = [];

    if (!deviceSn) rowErrors.push('设备编码必填');
    else if (!DEVICE_IDENTITY_PATTERN.test(deviceSn)) {
      rowErrors.push('设备编码仅允许字母、数字、点、下划线、短横线，且不超过 64 字符');
    }
    if (!deviceName) rowErrors.push('设备ID必填');
    else if (!DEVICE_IDENTITY_PATTERN.test(deviceName)) {
      rowErrors.push('设备ID仅允许字母、数字、点、下划线、短横线，且不超过 64 字符');
    }
    if (alias.length > 64) rowErrors.push('别名不能超过 64 个字符');
    if (remark.length > 255) rowErrors.push('备注不能超过 255 个字符');
    if (hasControlChar(alias)) rowErrors.push('别名不能包含控制字符');
    if (hasControlChar(remark)) rowErrors.push('备注不能包含控制字符');
    if (deviceSn && seenDeviceSn.has(deviceSn)) rowErrors.push(`设备编码重复：${deviceSn}`);
    if (deviceName && seenDeviceName.has(deviceName)) rowErrors.push(`设备ID重复：${deviceName}`);

    let enabled = 1;
    if (enabledText && !['1', '启用'].includes(enabledText)) {
      if (['0', '停用'].includes(enabledText)) enabled = 0;
      else rowErrors.push('启用状态只能填写启用或停用');
    }

    if (deviceSn) seenDeviceSn.add(deviceSn);
    if (deviceName) seenDeviceName.add(deviceName);
    if (rowErrors.length) {
      errors.push(`第 ${line} 行：${rowErrors.join('；')}`);
      return;
    }
    ok.push({ deviceSn, deviceName, alias, enabled, remark });
  });

  return { ok, errors };
}
