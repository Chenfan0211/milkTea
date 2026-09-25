function cloneState(state) {
  return {
    points: state.points,
    signedDates: [...state.signedDates],
    continuousDays: state.continuousDays,
    pointsRecords: state.pointsRecords.map(item => Object.assign({}, item))
  };
}

function formatDateKey(year, month, day) {
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

function buildMonthCells(year, month, signedDates) {
  const signedSet = new Set(signedDates);
  const firstWeekday = new Date(year, month - 1, 1).getDay();
  const daysInMonth = new Date(year, month, 0).getDate();
  return Array.from({ length: 42 }, (_, index) => {
    const day = index - firstWeekday + 1;
    if (day < 1 || day > daysInMonth) return { id: `empty-${index}`, key: '', day: '', empty: true, signed: false };
    const key = formatDateKey(year, month, day);
    return { id: key, key, day, empty: false, signed: signedSet.has(key) };
  });
}

function signInOnce(state, dateKey, dateTime) {
  if (state.signedDates.includes(dateKey)) {
    return { state: cloneState(state), awarded: false, record: null };
  }

  const nextState = cloneState(state);
  nextState.points += 1;
  nextState.continuousDays += 1;
  nextState.signedDates.push(dateKey);
  const isWeekComplete = nextState.continuousDays % 7 === 0;
  if (isWeekComplete) nextState.points += 20;
  const record = {
    id: `points-record-${dateKey}`,
    title: isWeekComplete ? '连续签到7天奖励' : '签到有礼',
    date: dateTime,
    amount: isWeekComplete ? '+21' : '+1',
    source: '签到有礼'
  };
  nextState.pointsRecords.unshift(record);
  return { state: nextState, awarded: true, record };
}

/** 生成最近7天（含今天）的周历数据；label 为 M.D 格式 */
function buildWeekDates(now = new Date(), signedDates = []) {
  const signedSet = new Set(signedDates || []);
  return Array.from({ length: 7 }, (_, offset) => {
    const date = new Date(now.getFullYear(), now.getMonth(), now.getDate() - (6 - offset));
    const key = `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
    return {
      key,
      label: `${date.getMonth() + 1}.${date.getDate()}`,
      today: offset === 6,
      signed: signedSet.has(key)
    };
  });
}

/** 根据签到日期集合计算连续签到天数（今天或昨天结尾均可连续） */
function calculateContinuousDays(signedDates, now = new Date()) {
  const set = new Set(signedDates || []);
  const keyOf = date => {
    const value = date instanceof Date ? date : new Date(date);
    return `${value.getFullYear()}-${String(value.getMonth() + 1).padStart(2, '0')}-${String(value.getDate()).padStart(2, '0')}`;
  };
  const dayMs = 24 * 60 * 60 * 1000;
  let cursor = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  if (!set.has(keyOf(cursor))) {
    cursor = new Date(cursor.getTime() - dayMs);
    if (!set.has(keyOf(cursor))) return 0;
  }
  let days = 0;
  while (set.has(keyOf(cursor))) {
    days += 1;
    cursor = new Date(cursor.getTime() - dayMs);
  }
  return days;
}

module.exports = {
  buildMonthCells,
  buildWeekDates,
  calculateContinuousDays,
  formatDateKey,
  signInOnce
};
