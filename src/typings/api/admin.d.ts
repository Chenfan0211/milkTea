declare namespace Api {
  namespace Admin {
    interface PageQuery {
      current: number;
      size: number;
    }

    interface PageResult<T> {
      records: T[];
      current: number;
      size: number;
      total: number;
    }

    interface Platform {
      code: string;
      name: string;
      type: string;
      accountStatus: string;
      accountInfo: string;
      signStatus: string;
      createTime: string;
    }

    interface Store {
      code: string;
      name: string;
      city: string;
      businessStatus: string;
      manager: string;
      location: string;
      investorName: string;
      createTime: string;
    }

    interface Channel {
      code: string;
      name: string;
      channelCode: string;
      boundUserCount: number;
      bindStatus: string;
      createTime: string;
    }

    interface Investor {
      code: string;
      name: string;
      investableStoreCount: number;
      relatedStore: string;
      signStatus: string;
      createTime: string;
    }

    interface Supplier {
      code: string;
      name: string;
      productCount: number;
      status: string;
      createTime: string;
    }

    interface Role {
      code: string;
      name: string;
      dataScope: string;
      createTime: string;
    }

    interface WechatBinding {
      openId: string;
      nickName: string;
      userId: string;
      roles: string[];
      bindStatus: string;
      lastLogin: string;
    }

    interface RoleGrant {
      userId: string;
      role: string;
      subject: string;
      dataScope: string;
      grantBy: string;
      grantTime: string;
      status: string;
    }

    interface Product {
      code: string;
      name: string;
      category: string;
      specCount: number;
      price: number;
      store: string;
      onSale: string;
      splitReady: string;
    }

    interface Spec {
      group: string;
      name: string;
      options: string[];
      order: number;
    }

    interface SplitRule {
      code: string;
      name: string;
      scope: string;
      platformRatio: number;
      storeRatio: number;
      channelRatio: number;
      investorRatio: number;
      supplierRatio: number;
      status: string;
    }

    interface Order {
      orderNo: string;
      store: string;
      user: string;
      summary: string;
      paidAmount: number;
      status: string;
      payStatus: string;
      pickupCode: string;
      createTime: string;
    }

    interface Payment {
      merchantOrderNo: string;
      paymentNo: string;
      amount: number;
      channel: string;
      thirdStatus: string;
      standardStatus: string;
      callbackTime: string;
    }

    interface Refund {
      refundNo: string;
      orderNo: string;
      amount: number;
      status: string;
      applyTime: string;
    }

    interface VerifyRecord {
      verifyCode: string;
      orderNo: string;
      store: string;
      operator: string;
      device: string;
      type: string;
      result: string;
      time: string;
    }

    interface SplitSnapshot {
      snapshotNo: string;
      orderNo: string;
      parties: string;
      platformAmount: number;
      storeAmount: number;
      channelAmount: number;
      investorAmount: number;
      supplierAmount: number;
      totalCheck: string;
      status: string;
      createTime: string;
    }

    interface SplitExecute {
      executeNo: string;
      snapshotNo: string;
      thirdRequestNo: string;
      status: string;
      executeTime: string;
    }

    interface Ledger {
      subject: string;
      role: string;
      orderNo: string;
      amount: number;
      status: string;
      carryTime: string;
      flowNo: string;
    }

    interface ReconcileIssue {
      issueType: string;
      orderNo: string;
      systemValue: string;
      thirdValue: string;
      diffAmount: number;
      foundTime: string;
      status: string;
    }

    interface AuditLog {
      time: string;
      operator: string;
      module: string;
      action: string;
      target: string;
      beforeValue: string;
      afterValue: string;
      reason: string;
      ip: string;
    }

    interface FeatureFlag {
      code: string;
      name: string;
      defaultStatus: string;
      currentStatus: string;
      openCondition: string;
    }
  }
}
