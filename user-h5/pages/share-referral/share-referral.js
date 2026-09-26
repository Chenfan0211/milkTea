const { withShare } = require('../../utils/share');
const auth = require('../../utils/auth');
const api = require('../../utils/api');

const DEFAULT_REFERRAL_REWARD_TEXT = '3 时光币 + 1 张 3 元无门槛券';

const DEFAULT_REFERRAL_CONFIG = {
  firstOrderPoints: 3,
  firstOrderCouponAmount: 3,
  socialStarThreshold: 5,
  socialStarProduct: '',
  recommenderThreshold: 10,
  recommenderRebateRate: 5
};

function toNonNegativeNumber(value, fallback) {
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? number : fallback;
}

function buildReferralView(input) {
  const config = Object.assign({}, DEFAULT_REFERRAL_CONFIG, input || {});
  const points = toNonNegativeNumber(config.firstOrderPoints, 3);
  const couponAmount = toNonNegativeNumber(config.firstOrderCouponAmount, 3);
  const socialStarThreshold = toNonNegativeNumber(config.socialStarThreshold, 5);
  const recommenderThreshold = toNonNegativeNumber(config.recommenderThreshold, 10);
  const recommenderRebateRate = toNonNegativeNumber(config.recommenderRebateRate, 5);
  const socialStarProduct = config.socialStarProduct
    ? ` + ${config.socialStarProduct}`
    : ' + 指定产品';
  const firstOrderRewardText =
    points === 3 && couponAmount === 3
      ? DEFAULT_REFERRAL_REWARD_TEXT
      : `${points} 时光币 + 1 张 ${couponAmount} 元无门槛券`;
  const recommenderRebateText = recommenderRebateRate === 5 ? '5%' : `${recommenderRebateRate}%`;

  return {
    rewards: [
      {
        id: 'first-order',
        title: '双方首单奖励',
        desc: `好友通过链接注册并完成首单，双方各得 ${firstOrderRewardText}`
      },
      {
        id: 'social-star',
        title: '社交达人',
        desc: `累计邀请满 ${socialStarThreshold} 人，额外解锁「社交达人」徽章${socialStarProduct}`
      },
      {
        id: 'recommender',
        title: '时光推荐官',
        desc: `累计邀请满 ${recommenderThreshold} 人，升级为「时光推荐官」，享受被邀请人后续消费 ${recommenderRebateText} 返利（示例，以后台配置为准）`
      }
    ],
    earningNote: `邀请好友注册 +${points}币/人，好友完成首单后到账。`,
    upgradeHint: `累计邀请满 ${recommenderThreshold} 人即可升级「时光推荐官」`
  };
}

const DEFAULT_REFERRAL_VIEW = buildReferralView(DEFAULT_REFERRAL_CONFIG);

/**
 * 分享有礼（邀请好友）。
 *
 * 设计要点：
 *   1. 本页不展示邀请码 —— 邀请关系由分享链接里的 referrerId 建立，
 *      不再依赖「让好友手输邀请码」这种易错且无法追溯的方式；
 *   2. 分享链接携带当前用户 userId（referrerId），好友注册时后端落库
 *      app_user.referrer_id，从而能准确记录「是哪个用户分享的」；
 *   3. 分享由「立即邀请好友」按钮通过 open-type="share" 直接唤起，
 *      不再引导用户自己去点右上角；
 *   4. 奖励展示读取后台 referral_config，接口失败时使用默认文案。
 */
Page(
  withShare(
    {
      onLoad() {
        api
          .fetchReferralCount()
          .then(res => {
            const count = Number(res && res.count);
            this.setData({ invitedCount: Number.isFinite(count) ? count : 0 });
          })
          .catch(() => {});

        api
          .fetchReferralConfig()
          .then(config => {
            this.setData(buildReferralView(config));
          })
          .catch(() => {
            // 配置接口异常时保留默认规则，不影响邀请和分享入口。
          });
      },
      data: {
        invitedCount: 0,
        rewards: DEFAULT_REFERRAL_VIEW.rewards,
        earningNote: DEFAULT_REFERRAL_VIEW.earningNote,
        upgradeHint: DEFAULT_REFERRAL_VIEW.upgradeHint
      }
    },
    {
      // 动态取当前用户 ID 作为邀请人标识。
      // 用函数而非固定值：登录态可能在页面存活期间才就绪，
      // 固定值会分享出空邀请人，导致推荐关系丢失。
      title: '五零时光分享有礼｜邀好友得时光币',
      get inviteReferrer() {
        const cached = auth.getCachedUser() || {};
        return cached.userId || '';
      }
    }
  )
);
