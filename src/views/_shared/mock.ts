const TOTAL = 23;

export type Loader = (params: { page: number; pageSize: number; search: Record<string, any> }) => Promise<{
  data: any[];
  total: number;
}>;

export function createDemoLoader(rowFactory: (index: number) => Record<string, any>): Loader {
  return async ({ page, pageSize, search }) => {
    let rows: Record<string, any>[] = Array.from({ length: TOTAL }, (_, i) => {
      const row = rowFactory(i);
      return { id: row.id ?? i + 1, ...row };
    });

    for (const key of Object.keys(search)) {
      const value = String(search[key] ?? '').trim();
      if (!value) continue;
      rows = rows.filter(row => {
        const target = row[key];
        if (Array.isArray(target)) return target.some(item => String(item).includes(value));
        return String(target ?? '').includes(value);
      });
    }

    const total = rows.length;
    const start = (page - 1) * pageSize;
    return { data: rows.slice(start, start + pageSize), total };
  };
}
