const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { buildMonthCells } = require('../../utils/points-signin');

function buildHint(rewards) {
  const first = rewards && rewards[0];
  return first ? `连续签到${first.days}天获得“${first.amount}时光币”` : '';
}

function buildRewards(rewardsSource, continuousDays, expanded) {
  const rewards = (rewardsSource || []).map(item =>
    Object.assign({}, item, {
      status: continuousDays >= item.days ? '已完成' : '待完成'
    })
  );
  return expanded ? rewards : rewards.slice(0, 2);
}

Page(
  withShare({
    data: {
      pointsSignIn: { year: 0, month: 0, today: '', weekDates: [] },
      points: 0,
      signedDates: [],
      signedToday: false,
      continuousDays: 0,
      weekDates: [],
      rewards: [],
      rewardsSource: [],
      hint: '',
      rewardsExpanded: false,
      calendarVisible: false,
      calendarYear: 0,
      calendarMonth: 0,
      weekdays: ['日', '一', '二', '三', '四', '五', '六'],
      calendarCells: [],
      successVisible: false,
      awardText: '1时光币'
    },
    onShow() {
      // 签到规则与奖励由后台配置（app_config.signin_rules / signin_rewards）
      api
        .fetchSigninConfig()
        .then(cfg => {
          if (!cfg) return;
          const rewards = Array.isArray(cfg.rewards) ? cfg.rewards : [];
          this.setData({
            rewardsSource: rewards,
            hint: buildHint(rewards),
            rewards: buildRewards(rewards, this.data.continuousDays, this.data.rewardsExpanded)
          });
        })
        .catch(() => null);
      this.syncSignInState();
      // 签到日历基准数据（app_config.points_signin）
      if (!this.data.pointsSignIn.year) {
        api
          .fetchConfig('points_signin')
          .then(cfg => {
            if (!cfg || !cfg.year) return;
            this.setData({
              pointsSignIn: cfg,
              calendarYear: cfg.year,
              calendarMonth: cfg.month,
              calendarCells: buildMonthCells(cfg.year, cfg.month, this.data.signedDates)
            });
            this.syncSignInState();
          })
          .catch(() => null);
      }
    },
    syncSignInState() {
      const app = getApp();
      const state = app.globalData;
      const calendar = this.data.pointsSignIn || { weekDates: [], today: '' };
      const weekDates = (calendar.weekDates || []).map(item =>
        Object.assign({}, item, {
          signed: state.signedDates.includes(item.key)
        })
      );
      this.setData({
        points: state.points,
        signedDates: state.signedDates,
        signedToday: Boolean(calendar.today) && state.signedDates.includes(calendar.today),
        continuousDays: state.continuousDays,
        weekDates,
        rewards: buildRewards(this.data.rewardsSource, state.continuousDays, this.data.rewardsExpanded),
        calendarCells: buildMonthCells(this.data.calendarYear, this.data.calendarMonth, state.signedDates)
      });
    },
    handleSignIn() {
      if (this.data.signedToday) {
        wx.showToast({ title: '今日已签到', icon: 'none' });
        return;
      }

      // 签到为服务端写操作：时光币与连续天数均以后端为准
      const calendar = this.data.pointsSignIn || {};
      const app = getApp();
      api
        .signIn()
        .then(result => {
          const balance = result && Number.isFinite(Number(result.balance))
            ? Number(result.balance)
            : Number(app.globalData.points || 0) + 1;
          app.globalData.points = balance;
          if (calendar.today) {
            app.globalData.signedDates = (app.globalData.signedDates || []).concat([calendar.today]);
          }
          app.globalData.continuousDays = Number(app.globalData.continuousDays || 0) + 1;
          const isWeekComplete = app.globalData.continuousDays % 7 === 0;
          this.setData({ successVisible: true, awardText: (isWeekComplete ? '21' : '1') + '时光币' });
          this.syncSignInState();
        })
        .catch(error => {
          wx.showToast({ title: (error && error.message) || '签到失败，请稍后重试', icon: 'none' });
        });
    },
    closeSuccess() {
      this.setData({ successVisible: false });
    },
    openCalendar() {
      this.setData({ calendarVisible: true });
      this.syncSignInState();
    },
    closeCalendar() {
      this.setData({ calendarVisible: false });
    },
    changeMonth(event) {
      const delta = Number(event.currentTarget.dataset.delta);
      let calendarYear = this.data.calendarYear;
      let calendarMonth = this.data.calendarMonth + delta;
      if (calendarMonth < 1) {
        calendarMonth = 12;
        calendarYear -= 1;
      } else if (calendarMonth > 12) {
        calendarMonth = 1;
        calendarYear += 1;
      }
      this.setData({
        calendarYear,
        calendarMonth,
        calendarCells: buildMonthCells(calendarYear, calendarMonth, this.data.signedDates)
      });
    },
    toggleRewards() {
      const rewardsExpanded = !this.data.rewardsExpanded;
      this.setData({
        rewardsExpanded,
        rewards: buildRewards(this.data.rewardsSource, this.data.continuousDays, rewardsExpanded)
      });
    },
    openRules() {
      wx.navigateTo({ url: '/pages/points-signin-rules/points-signin-rules' });
    },
    handleRewards() {
      wx.showToast({ title: '我的奖品暂未接入', icon: 'none' });
    },
    noop() {}
  })
);


