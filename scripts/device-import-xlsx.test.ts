import assert from 'node:assert/strict';
import ExcelJS from 'exceljs';
import type { ImportConfig } from '../src/views/_shared/types';
import { parseDeviceImportRows } from '../src/views/subject/device/import';
import {
  assertNormalizedRowsSize,
  assertXlsxFile,
  buildTemplateWorkbook,
  createXlsxTemplateDownload,
  parseXlsxRowsFromArrayBuffer
} from '../src/views/_shared/xlsx';

const cfg: ImportConfig = {
  title: '设备',
  sheetName: '设备导入',
  maxRows: 2,
  fields: [
    { key: 'deviceSn', label: '设备编码', required: true },
    { key: 'deviceName', label: '设备ID', required: true },
    { key: 'enabled', label: '启用状态', options: ['启用', '停用'] }
  ],
  template: () => [['设备编码', '设备ID', '启用状态']],
  parse: rows => ({ ok: rows, errors: [] }),
  commit: () => ({ added: 0, skipped: 0 })
};

async function workbookBuffer(rows: any[][]): Promise<ArrayBuffer> {
  const workbook = new ExcelJS.Workbook();
  const sheet = workbook.addWorksheet('设备导入');
  rows.forEach(row => sheet.addRow(row));
  return (await workbook.xlsx.writeBuffer()) as ArrayBuffer;
}

async function run() {
  const templateBuffer = await buildTemplateWorkbook(cfg);
  const templateDownload = await createXlsxTemplateDownload(cfg);
  assert.equal(templateDownload.fileName, '设备导入模板.xlsx');
  assert.equal(templateDownload.blob.type, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
  const templateWorkbook = new ExcelJS.Workbook();
  await templateWorkbook.xlsx.load(Buffer.from(templateBuffer) as any);
  assert.deepEqual(
    templateWorkbook.worksheets.map(sheet => sheet.name),
    ['设备导入', '填写说明']
  );
  assert.equal(templateWorkbook.getWorksheet('设备导入')?.getCell('A1').value, '设备编码');
  assert.ok(templateWorkbook.getWorksheet('设备导入')?.getCell('C2').dataValidation);
  assert.deepEqual(await parseXlsxRowsFromArrayBuffer(templateBuffer, cfg), []);

  const valid = await workbookBuffer([
    ['设备编码', '设备ID', '启用状态'],
    ['ZPDS-001', '869978072404913', '启用']
  ]);
  assert.deepEqual(await parseXlsxRowsFromArrayBuffer(valid, cfg), [
    { deviceSn: 'ZPDS-001', deviceName: '869978072404913', enabled: '启用' }
  ]);

  const formulaWorkbook = new ExcelJS.Workbook();
  const formulaSheet = formulaWorkbook.addWorksheet('设备导入');
  formulaSheet.addRow(['设备编码', '设备ID', '启用状态']);
  formulaSheet.addRow(['ZPDS-001', '869978072404913', '启用']);
  formulaSheet.getCell('A2').value = { formula: '1+1', result: 2 };
  const formulaBuffer = (await formulaWorkbook.xlsx.writeBuffer()) as ArrayBuffer;
  await assert.rejects(() => parseXlsxRowsFromArrayBuffer(formulaBuffer, cfg), /不允许使用公式/);

  const tooMany = await workbookBuffer([
    ['设备编码', '设备ID', '启用状态'],
    ['SN-1', 'ID-1', '启用'],
    ['SN-2', 'ID-2', '启用'],
    ['SN-3', 'ID-3', '启用']
  ]);
  await assert.rejects(() => parseXlsxRowsFromArrayBuffer(tooMany, cfg), /最多 2 行/);

  const multiSheetWorkbook = new ExcelJS.Workbook();
  multiSheetWorkbook.addWorksheet('设备导入').addRow(['设备编码', '设备ID', '启用状态']);
  multiSheetWorkbook.addWorksheet('另一个数据表').addRow(['x']);
  const multiSheetBuffer = (await multiSheetWorkbook.xlsx.writeBuffer()) as ArrayBuffer;
  await assert.rejects(() => parseXlsxRowsFromArrayBuffer(multiSheetBuffer, cfg), /只能包含一个数据工作表/);
  await assert.rejects(
    () => parseXlsxRowsFromArrayBuffer(new Uint8Array([1, 2, 3, 4]).buffer, cfg),
    /把内容读入工作簿失败|Corrupt|zip file|not a zip/i
  );

  await assert.rejects(() => assertXlsxFile(new File(['x'], 'devices.csv')), /仅支持 \.xlsx/);
  await assert.rejects(() => assertXlsxFile(new File(['x'], 'devices.xls')), /仅支持 \.xlsx/);
  await assert.rejects(() => assertXlsxFile(new File(['x'], 'devices.xlsm')), /仅支持 \.xlsx/);
  await assert.rejects(() => assertXlsxFile(new File([new Uint8Array(10 * 1024 * 1024 + 1)], 'devices.xlsx')), /10MB/);
  await assert.rejects(() => assertXlsxFile(new File([new Uint8Array(4)], 'devices.xlsx')), /有效的 XLSX/);
  assert.throws(() => assertNormalizedRowsSize([{ remark: 'x'.repeat(6 * 1024 * 1024) }]), /请拆分文件/);

  const unsafeRows = parseDeviceImportRows([
    { deviceSn: "SN' OR 1=1 --", deviceName: 'ID-1' },
    { deviceSn: 'SN-2', deviceName: 'ID\u00002' },
    { deviceSn: 'SN-3', deviceName: 'ID-3', alias: 'bad\u0001alias' },
    { deviceSn: 'SN-4', deviceName: 'ID-4' },
    { deviceSn: 'SN-4', deviceName: 'ID-5' }
  ]);
  assert.equal(unsafeRows.ok.length, 1);
  assert.equal(unsafeRows.errors.length, 4);
  assert.match(unsafeRows.errors.join('\n'), /仅允许字母/);
  assert.match(unsafeRows.errors.join('\n'), /控制字符/);
  assert.match(unsafeRows.errors.join('\n'), /设备编码重复/);
}

run().then(() => {
  console.log('device-import-xlsx tests passed');
});
