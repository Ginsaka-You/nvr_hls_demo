const app = getApp();

Page({
  data: {
    userId: '',
    templateId: ''
  },
  onLoad() {
    const stored = wx.getStorageSync('userId');
    const userId = stored || app.globalData.userId || '';
    const templateId = app.globalData.templateId || '';
    this.setData({ userId, templateId });
  },
  onUserIdInput(e) {
    this.setData({ userId: e.detail.value });
  },
  async handleSubscribe() {
    if (!this.data.userId) {
      wx.showToast({ title: '请输入用户ID', icon: 'none' });
      return;
    }
    try {
      const res = await wx.requestSubscribeMessage({
        tmplIds: [this.data.templateId]
      });
      if (res[this.data.templateId] !== 'accept') {
        wx.showToast({ title: '未授权订阅', icon: 'none' });
        return;
      }
      await wx.cloud.callFunction({
        name: 'bindUser',
        data: { userId: this.data.userId }
      });
      wx.setStorageSync('userId', this.data.userId);
      app.globalData.userId = this.data.userId;
      wx.showToast({ title: '订阅成功', icon: 'success' });
    } catch (err) {
      console.error('subscribe fail', err);
      wx.showToast({ title: '操作失败', icon: 'none' });
    }
  }
});
