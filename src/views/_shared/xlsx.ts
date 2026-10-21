import type { ImportConfig, ImportField } from './types';

export const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
export const XLSX_MAX_FILE_SIZE = 10 * 1024 * 1024;
export const XLSX_MAX_ROWS = 5000;
export const MAX_NORMALIZED_JSON_SIZE = 6 * 1024 * 1024;
const INSTRUCTION_SHEET = '填写说明';

async function loadExcelJS(): Promise<any> {
  const module = await import('exceljs');
  return (module as any).default ?? module;
}

function fieldWidth(field: ImportField): number {
  return field.width ?? Math.max(12, Math.min(30, field.label.length * 2 + 6));
}

function normalizeCell(value: any): string {
  if (value == null) return '';
  if (value instanceof Date) return value.toISOString();
  if (typeof value === 'object') {
    if ('formula' in value || 'sharedFormula' in value || 'sharedFormulaId' in value) {
      throw new Error('XLSX 中不允许使用公式单元格');
    }
    if ('richText' in value && Array.isArray(value.richText)) {
      return value.richText
        .map((part: any) => String(part.text ?? ''))
        .join('')
        .trim();
    }
    if ('text' in value) return String(value.text ?? '').trim();
    return String(value).trim();
  }
  return String(value).trim();
}

function headerValues(worksheet: any, count: number): string[] {
  const row = worksheet.getRow(1);
  return Array.from({ length: count }, (_, index) => normalizeCell(row.getCell(index + 1).value));
}

function selectDataWorksheet(workbook: any, cfg: ImportConfig): any {
  const preferredName = cfg.sheetName ?? cfg.title;
  const candidates = workbook.worksheets.filter((sheet: any) => sheet.name !== INSTRUCTION_SHEET);
  if (candidates.length !== 1) {
    throw new Error(`XLSX 只能包含一个数据工作表（${preferredName}）和一个填写说明表`);
  }
  const worksheet = candidates[0];
  if (worksheet.name !== preferredName) {
    throw new Error(`XLSX 数据工作表名称应为：${preferredName}`);
  }
  return worksheet;
}

export async function parseXlsxRowsFromArrayBuffer(
  data: ArrayBuffer,
  cfg: ImportConfig
): Promise<Record<string, string>[]> {
  const ExcelJS = await loadExcelJS();
  const workbook = new ExcelJS.Workbook();
  await workbook.xlsx.load(data);
  const worksheet = selectDataWorksheet(workbook, cfg);

  const expectedHeaders = cfg.fields.map(field => field.label);
  const actualHeaders = headerValues(worksheet, expectedHeaders.length);
  if (actualHeaders.some((header, index) => header !== expectedHeaders[index])) {
    throw new Error(`XLSX 表头不匹配，应为：${expectedHeaders.join('、')}`);
  }

  const maxRows = cfg.maxRows ?? XLSX_MAX_ROWS;
  if (worksheet.actualRowCount > maxRows + 1) {
    throw new Error(`单次导入最多 ${maxRows} 行，请拆分文件后重试`);
  }
  const rows: Record<string, string>[] = [];
  for (let rowIndex = 2; rowIndex <= worksheet.actualRowCount; rowIndex += 1) {
    const row = worksheet.getRow(rowIndex);
    const parsed: Record<string, string> = {};
    let hasValue = false;
    cfg.fields.forEach((field, fieldIndex) => {
      const value = normalizeCell(row.getCell(fieldIndex + 1).value);
      parsed[field.key] = value;
      hasValue ||= Boolean(value);
    });
    if (!hasValue) continue;
    rows.push(parsed);
    if (rows.length > maxRows) {
      throw new Error(`单次导入最多 ${maxRows} 行，请拆分文件后重试`);
    }
  }
  return rows;
}

export async function assertXlsxFile(file: File, maxFileSize = XLSX_MAX_FILE_SIZE): Promise<void> {
  if (!file) throw new Error('请选择要导入的 XLSX 文件');
  if (!/\.xlsx$/i.test(file.name)) throw new Error('仅支持 .xlsx 文件');
  if (file.size > maxFileSize) {
    throw new Error(`文件不能超过 ${Math.floor(maxFileSize / 1024 / 1024)}MB`);
  }
  const head = new Uint8Array(await file.slice(0, 4).arrayBuffer());
  const isZip = head.length === 4 && head[0] === 0x50 && head[1] === 0x4b && head[2] === 0x03 && head[3] === 0x04;
  if (!isZip) throw new Error('文件不是有效的 XLSX 工作簿');
}

export async function readXlsxRows(file: File, cfg: ImportConfig): Promise<Record<string, string>[]> {
  await assertXlsxFile(file, cfg.maxFileSize ?? XLSX_MAX_FILE_SIZE);
  return parseXlsxRowsFromArrayBuffer(await file.arrayBuffer(), cfg);
}

export async function buildTemplateWorkbook(cfg: ImportConfig): Promise<ArrayBuffer> {
  const ExcelJS = await loadExcelJS();
  const workbook = new ExcelJS.Workbook();
  const worksheet = workbook.addWorksheet(cfg.sheetName ?? cfg.title);
  const templateRows = cfg.template();
  templateRows.forEach((row: any[]) => worksheet.addRow(row));

  worksheet.views = [{ state: 'frozen', ySplit: 1 }];
  worksheet.columns = cfg.fields.map(field => ({ key: field.key, width: fieldWidth(field) }));
  worksheet.getRow(1).font = { bold: true };
  worksheet.getRow(1).fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFEAF3E3' } };
  worksheet.getRow(1).alignment = { vertical: 'middle', horizontal: 'center' };

  cfg.fields.forEach((field, index) => {
    if (!field.options?.length) return;
    for (let row = 2; row <= (cfg.maxRows ?? XLSX_MAX_ROWS) + 1; row += 1) {
      worksheet.getCell(row, index + 1).dataValidation = {
        type: 'list',
        allowBlank: !field.required,
        formulae: [`"${field.options.join(',')}"`],
        showErrorMessage: true,
        errorTitle: '输入不合法',
        error: `请选择：${field.options.join('、')}`
      };
    }
  });

  const instruction = workbook.addWorksheet(INSTRUCTION_SHEET);
  instruction.columns = [{ width: 22 }, { width: 90 }];
  instruction.addRows(
    cfg.instructions ?? [
      ['字段', '填写说明'],
      ...cfg.fields.map(field => [field.label, field.required ? '必填' : '选填'])
    ]
  );
  instruction.getRow(1).font = { bold: true };
  instruction.getRow(1).fill = { type: 'pattern', pattern: 'solid', fgColor: { argb: 'FFEAF3E3' } };

  return (await workbook.xlsx.writeBuffer()) as ArrayBuffer;
}

export async function downloadXlsxTemplate(cfg: ImportConfig): Promise<void> {
  const { blob, fileName } = await createXlsxTemplateDownload(cfg);
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

export async function createXlsxTemplateDownload(cfg: ImportConfig): Promise<{ blob: Blob; fileName: string }> {
  const buffer = await buildTemplateWorkbook(cfg);
  const blob = new Blob([buffer], { type: XLSX_MIME });
  return { blob, fileName: `${cfg.title}导入模板.xlsx` };
}

export function assertNormalizedRowsSize(rows: Record<string, any>[], maxSize = MAX_NORMALIZED_JSON_SIZE): void {
  const size = new TextEncoder().encode(JSON.stringify(rows)).byteLength;
  if (size > maxSize) {
    throw new Error(`导入数据转换后超过 ${Math.floor(maxSize / 1024 / 1024)}MB，请拆分文件后重试`);
  }
}
