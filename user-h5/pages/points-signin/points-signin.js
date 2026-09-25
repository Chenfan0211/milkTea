const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { buildMonthCells, buildWeekDates, calculateContinuousDays } = require('../../utils/points-signin');
const { getPoints, setPoints } = require('../../utils/points');

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
      this.syncSignInState();
      // 签到状态以后端为准：拉取用户全部签到日期后还原周历 / 月历 / 连续天数
      api
        .fetchSigninDates()
        .then(dates => {
          const signedDates = Array.isArray(dates) ? dates : [];
          const app = getApp();
          app.globalData.signedDates = signedDates;
          app.globalData.continuousDays = calculateContinuousDays(signedDates);
          this.signinStateReady = true;
          this.syncSignInState();
        })
        .catch(() => {
          // 状态拉取失败：标记未就绪，避免把「拉取失败」误判为「今天没签到」
          this.signinStateReady = false;
        });
      // 签到规则与奖励由后台配置
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
    },
    syncSignInState() {
      const app = getApp();
      const signedDates = app.globalData.signedDates || [];
      const now = new Date();
      const weekDates = buildWeekDates(now, signedDates);
      const todayKey = weekDates[weekDates.length - 1].key;
      const continuousDays = calculateContinuousDays(signedDates, now);
      this.setData({
        points: getPoints(),
        signedDates,
        todayKey,
        signedToday: signedDates.includes(todayKey),
        continuousDays,
        weekDates,
        rewards: buildRewards(this.data.rewardsSource, continuousDays, this.data.rewardsExpanded),
        calendarCells: buildMonthCells(
          this.data.calendarYear || now.getFullYear(),
          this.data.calendarMonth || now.getMonth() + 1,
          signedDates
        )
      });
    },
    handleSignIn() {
      if (this.data.signedToday) {
        wx.showToast({ title: '今日已签到', icon: 'none' });
        return;
      }
      // 说明：状态拉取失败（signinStateReady=false）时本地「未签到」不可信，
      // 这里不做拦截，直接提交给后端；由后端判重兜底，失败分支再反向修正 UI。

      // 签到为服务端写操作：时光币以后端返回余额为准，并同步本地持久缓存
      const app = getApp();
      const previousPoints = getPoints();
      api
        .signIn()
        .then(result => {
          const balance = result && Number.isFinite(Number(result.balance))
            ? Number(result.balance)
            : previousPoints + 1;
          setPoints(balance);
          const now = new Date();
          const pad = n => String(n).padStart(2, '0');
          const todayKey = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
          if (!app.globalData.signedDates.includes(todayKey)) {
            app.globalData.signedDates = app.globalData.signedDates.concat([todayKey]);
          }
          app.globalData.continuousDays = calculateContinuousDays(app.globalData.signedDates, now);
          const awarded = Math.max(1, balance - previousPoints);
          this.setData({ successVisible: true, awardText: awarded + '时光币' });
          this.syncSignInState();
        })
        .catch(error => {
          const message = (error && error.message) || '签到失败，请稍后重试';
          // 后端判重（今日已签到）时，前端状态是错的：反向修正为已签到，
          // 并重新拉取签到日期，保证 UI 与后端一致。
          if (String(message).indexOf('已签到') >= 0) {
            this.markSignedToday();
            this.refreshSigninDates();
          }
          wx.showToast({ title: message, icon: 'none' });
        });
    },
    /** 把「今天」标记为已签到（用于后端判重后的状态自纠正）。 */
    markSignedToday() {
      const app = getApp();
      const now = new Date();
      const pad = n => String(n).padStart(2, '0');
      const todayKey = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
      if (!(app.globalData.signedDates || []).includes(todayKey)) {
        app.globalData.signedDates = (app.globalData.signedDates || []).concat([todayKey]);
      }
      app.globalData.continuousDays = calculateContinuousDays(app.globalData.signedDates, now);
      this.syncSignInState();
    },

    /** 重新从后端拉取签到日期（失败时保持当前状态）。 */
    refreshSigninDates() {
      api
        .fetchSigninDates()
        .then(dates => {
          const signedDates = Array.isArray(dates) ? dates : [];
          const app = getApp();
          app.globalData.signedDates = signedDates;
          app.globalData.continuousDays = calculateContinuousDays(signedDates);
          this.signinStateReady = true;
          this.syncSignInState();
        })
        .catch(() => null);
    },

    closeSuccess() {
      this.setData({ successVisible: false });
    },
    openCalendar() {
      this.setData({
        calendarVisible: true,
        calendarYear: this.data.calendarYear || new Date().getFullYear(),
        calendarMonth: this.data.calendarMonth || new Date().getMonth() + 1
      });
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
    noop() {}
  })
);
