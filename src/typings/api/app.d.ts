declare namespace Api {
  namespace App {
    /** 门店类型字典项（小程序加盟申请使用） */
    interface StoreType {
      id: number;
      code: string;
      name: string;
      sort: number;
      enabled: boolean;
    }
  }
}