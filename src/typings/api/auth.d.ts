declare namespace Api {
  /**
   * namespace Auth
   *
   * backend api module: "auth"
   */
  namespace Auth {
    interface LoginToken {
      token: string;
      refreshToken: string;
    }

    interface UserInfo {
      userId: string;
      userName: string;
      roles: string[];
      buttons: string[];
      /**
       * 是否超级管理员账号（后端 sys_user.is_super）。
       *
       * 用途：前端据此隐藏「账号管理」页里超管行的危险操作按钮
       * （编辑/停用/删除/改角色）。真正的拦截在服务端 AdminRbacGuard ——
       * 前端隐藏只是避免「点了才被拒」的糟糕体验。
       */
      isSuper?: boolean;
    }
  }
}
