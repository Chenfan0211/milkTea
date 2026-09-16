function cloneState(state) {
  return {
    points: state.points,
    signedDates: [...state.signedDates],
    continuousDays: state.continuousDays,
    pointsRecords: state.pointsRecords.map(item => Object.assign({}, item))
  }
}

function formatDateKey(year, month, day) {
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`
}

function buildMonthCells(year, month, signedDates) {
  const signedSet = new Set(signedDates)
  const firstWeekday = new Date(year, month - 1, 1).getDay()
  const daysInMonth = new Date(year, month, 0).getDate()
  return Array.from({ length: 42 }, (_, index) => {
    const day = index - firstWeekday + 1
    if (day < 1 || day > daysInMonth) return { id: `empty-${index}`, key: '', day: '', empty: true, signed: false }
    const key = formatDateKey(year, month, day)
    return { id: key, key, day, empty: false, signed: signedSet.has(key) }
  })
}

function signInOnce(state, dateKey, dateTime) {
  if (state.signedDates.includes(dateKey)) {
    return { state: cloneState(state), awarded: false, record: null }
  }

  const record = {
    id: `points-record-${dateKey}`,
    title: '签到有礼',
    date: dateTime,
    amount: '+1',
    source: '签到有礼'
  }
  const nextState = cloneState(state)
  nextState.points += 1
  nextState.continuousDays += 1
  nextState.signedDates.push(dateKey)
  nextState.pointsRecords.unshift(record)
  return { state: nextState, awarded: true, record }
}

module.exports = {
  buildMonthCells,
  formatDateKey,
  signInOnce
}
