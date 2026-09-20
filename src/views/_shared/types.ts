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
  options?: SelectOption[];
  placeholder?: string;
}

export interface FormField {
  key: string;
  label: string;
  type?: 'input' | 'select' | 'number';
  options?: SelectOption[];
  placeholder?: string;
}

export interface RowAction {
  label: string;
  type?: TagType;
  confirm?: string;
  modal?: 'add' | 'edit';
  /** show a single-select picker before invoking handler */
  picker?: {
    title: string;
    options: () => SelectOption[];
  };
  handler?: (row: any, picked?: string) => unknown;
  visible?: (row: any) => boolean;
}

export interface AdminListConfig {
  title: string;
  columns: DataTableColumns<any>;
  searchFields?: SearchField[];
  toolbar?: RowAction[];
  rowActions?: RowAction[];
  loadData: (params: { page: number; pageSize: number; search: Record<string, any> }) => Promise<{
    data: any[];
    total: number;
  }>;
  form?: {
    title: string;
    fields: FormField[];
    onSubmit: (data: Record<string, any>, editing: any | null) => void | Promise<void>;
  };
}

export function toSelectOptions(options: SelectOption[]): { label: string; value: string }[] {
  return options.map(item => ({ label: item.label, value: item.value }));
}
