import type { DataTableColumns } from 'naive-ui';

export type TagType = 'default' | 'error' | 'primary' | 'info' | 'success' | 'warning';

export type TableAlign = 'left' | 'right' | 'center';

export interface SelectOption {
  label: string;
  value: string;
}

export interface SearchField {
  key: string;
  label: string;
  type?: 'input' | 'select';
  options?: SelectOption[] | (() => SelectOption[]);
  placeholder?: string;
}

export interface FormField {
  key: string;
  label: string;
  rules?: any[];
  type?: 'input' | 'select' | 'number' | 'multiple' | 'image' | 'date' | 'textarea' | 'geocode' | 'password';
  options?: SelectOption[] | (() => SelectOption[]);
  placeholder?: string;
  multiple?: boolean;
  /** 文本输入最大长度（如左侧分组标签限 5 字，保证小程序单行完整显示） */
  maxlength?: number;
  /** geocode 类型：从 sourceKey 地址解析，回填到 latKey / lngKey */
  geocodeSourceKey?: string;
  geocodeLatKey?: string;
  geocodeLngKey?: string;
}

export interface RowAction {
  label: string;
  type?: TagType;
  confirm?: string;
  modal?: 'add' | 'edit';
  /** 操作前强制填写备注，并传入 handler 第二参数 */
  reasonPrompt?: string;
  /** show a single-select picker before invoking handler */
  picker?: {
    title: string;
    options: (row?: any) => SelectOption[];
  };
  /** show one select with grouped options before invoking handler; picked value = "group:value" */
  pickerGroup?: {
    title: string;
    groups: { label: string; key: string; options: () => SelectOption[] }[];
  };
  /** 两步级联选择：先选第一级，再选第二级（第二级依赖第一级） */
  cascadePicker?: {
    title: string;
    steps: [
      { label: string; options: () => SelectOption[] },
      { label: string; options: (firstValue: string) => SelectOption[] }
    ];
    handler: (row: any, values: { first: string; second: string }) => unknown;
  };
  handler?: (row: any, picked?: string, groupKey?: string, groupValue?: string) => unknown;
  visible?: (row: any) => boolean;
}

export interface ImportField {
  key: string;
  label: string;
  required?: boolean;
}

export interface ImportConfig {
  title: string;
  fields: ImportField[];
  /** 生成模板（CSV 字符串，首行为表头） */
  template: () => string;
  /** 解析并校验导入数据，返回合法行与错误信息 */
  parse: (rows: Record<string, string>[]) => { ok: Record<string, any>[]; errors: string[] };
  /** 批量提交（写入 store） */
  /** 批量提交。远端模式下为异步写库，故允许返回 Promise。 */
  commit: (rows: Record<string, any>[]) => { added: number; skipped: number } | Promise<{ added: number; skipped: number }>;
}
export interface AdminListConfig {
  title: string;
  /**
   * 远端资源键（对应 adminStore 的 REMOTE_RESOURCES）。
   * 配置后页面挂载时会先从后端拉取该资源，未配置则继续使用本地数据。
   */
  remoteKey?: string;
  /** 需要预加载的其他远端资源（如下拉选项依赖的 provinces） */
  remoteDeps?: string[];
  /** 从菜单重新进入页面（onActivated）时是否重新查询后端数据；默认 true，仅当显式设为 false 时关闭 */
  refreshOnEnter?: boolean;
  columns: DataTableColumns<any>;
  searchFields?: SearchField[];
  /** 初始搜索条件（进入页面时合入搜索表单，可用于 URL 回显） */
  initialSearch?: Record<string, any>;
  toolbar?: RowAction[];
  rowActions?: RowAction[];
  loadData: (params: { page: number; pageSize: number; search: Record<string, any> }) => Promise<{
    data: any[];
    total: number;
  }>;
  form?: {
    title: string;
    fields: FormField[];
    /**
     * 打开表单时对行数据做预处理（如「分 -> 元」换算、字段改名）。
     * 不配置则直接以行数据回填。
     */
    toFormData?: (row: any) => Record<string, any>;
    onSubmit: (data: Record<string, any>, editing: any | null) => void | Promise<void>;
  };
  importConfig?: ImportConfig;
}

export function toSelectOptions(options: SelectOption[]): { label: string; value: string }[] {
  return options.map(item => ({ label: item.label, value: item.value }));
}

