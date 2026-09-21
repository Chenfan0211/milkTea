const { withShare } = require('../../utils/share');
const api = require('../../utils/api');
const { pointsSignIn, signInRewards } = require('../../data/mock');
const { setPoints } = require('../../utils/points');
const { buildMonthCells, signInOnce } = require('../../utils/points-signin');

function buildHint(rewards) {
  const first = rewards && rewards[0];
  return first ? `连续签到${first.days}天获得“${first.amount}时光币”` : '';
}

function buildRewards(continuousDays, expanded) {
  const rewards = signInRewards.map(item =>
    Object.assign({}, item, {
      status: continuousDays >= item.days ? '已完成' : '待完成'
    })
  );
  return expanded ? rewards : rewards.slice(0, 2);
}

Page(
  withShare({
    data: {
      pointsSignIn,
      points: 0,
      signedDates: [],
      signedToday: false,
      continuousDays: 0,
      weekDates: [],
      rewards: buildRewards(0, false),
      hint: buildHint(signInRewards),
      rewardsExpanded: false,
      calendarVisible: false,
      calendarYear: pointsSignIn.year,
      calendarMonth: pointsSignIn.month,
      weekdays: ['日', '一', '二', '三', '四', '五', '六'],
      calendarCells: buildMonthCells(pointsSignIn.year, pointsSignIn.month, []),
      successVisible: false,
      awardText: '1时光币'
    },
    onShow() {
      // 签到奖励档位由后台配置（app_config.signin_rewards）
      api
        .fetchSigninConfig()
        .then(cfg => {
          if (cfg && Array.isArray(cfg.rewards) && cfg.rewards.length) {
            this.setData({ rewards: buildRewards(this.data.continuousDays, false) });
          }
        })
        .catch(() => null);
      this.syncSignInState();
    },
    syncSignInState() {
      const app = getApp();
      const state = app.globalData;
      const weekDates = pointsSignIn.weekDates.map(item =>
        Object.assign({}, item, {
          signed: state.signedDates.includes(item.key)
        })
      );
      this.setData({
        points: state.points,
        signedDates: state.signedDates,
        signedToday: state.signedDates.includes(pointsSignIn.today),
        continuousDays: state.continuousDays,
        weekDates,
        rewards: buildRewards(state.continuousDays, this.data.rewardsExpanded),
        calendarCells: buildMonthCells(this.data.calendarYear, this.data.calendarMonth, state.signedDates)
      });
    },
    handleSignIn() {
      if (this.data.signedToday) {
        wx.showToast({ title: '今日已签到', icon: 'none' });
        return;
      }

      const app = getApp();
      const result = signInOnce(
        {
          points: app.globalData.points,
          signedDates: app.globalData.signedDates,
          continuousDays: app.globalData.continuousDays,
          pointsRecords: app.globalData.pointsRecords
        },
        pointsSignIn.today,
        `${pointsSignIn.today} 00:36:15`
      );

      app.globalData.points = result.state.points;
      setPoints(result.state.points);
      app.globalData.signedDates = result.state.signedDates;
      app.globalData.continuousDays = result.state.continuousDays;
      app.globalData.pointsRecords = result.state.pointsRecords;
      const isWeekComplete = result.state.continuousDays % 7 === 0;
      this.setData({ successVisible: true, awardText: (isWeekComplete ? '21' : '1') + '时光币' });
      this.syncSignInState();
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
        rewards: buildRewards(this.data.continuousDays, rewardsExpanded)
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


