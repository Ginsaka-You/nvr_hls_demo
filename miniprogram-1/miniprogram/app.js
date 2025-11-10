App({
  globalData: {
    envId: 'cloud1-0grqej0tb7ac6745',
    defaultUserId: 'demo-admin',
    templateId: 'lKtnQJIVx4mFSs9QJWm6xhL5in6KYOsrcdmhpNzCLfQ'
  },
  onLaunch() {
    wx.cloud.init({
      env: this.globalData.envId,
      traceUser: true
    });
  }
});
