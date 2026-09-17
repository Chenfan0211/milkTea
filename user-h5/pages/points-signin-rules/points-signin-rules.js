const { withShare } = require('../../utils/share')
const { signInRules } = require('../../data/mock')

Page(withShare({
  data: {
    signInRules
  }
}))
