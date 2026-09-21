const { withShare } = require('../../utils/share');
const { menuActivity } = require('../../data/mock');

Page(
  withShare({
    data: {
      menuActivity
    }
  })
);
