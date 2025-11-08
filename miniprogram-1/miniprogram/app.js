App({
  globalData: {
    envId: 'cloud1-0grqej0tb7ac6745',
    userId: 'user-demo-001',
    templateId: 'lKtnQJIVx4mFSs9QJWm6xhL5in6KYOsrcdmhpNzCLfQD'
  },
  onLaunch() {
    wx.cloud.init({
      env: this.globalData.envId,
      traceUser: true
    });
  }
});
