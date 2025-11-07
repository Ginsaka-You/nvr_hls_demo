const SUBSCRIBE_TEMPLATE_IDS = ['TEMPLATE_ID_FROM_WECHAT'];
const REPORT_ENDPOINT = 'https://alerts.nvr-hls-demo.com/api/wechat/subscription/report';
const REPORT_SCENE = 'alert-center';

Page({
  data: {
    loading: false,
    lastSubscribeStatus: '',
    guideSteps: [
      '点击下方“订阅报警提醒”按钮，按提示授权对应模板',
      '授权成功后，系统会自动记录 OpenID，并在有新报警时通过服务通知推送',
      '每次授权可发送一条提醒，如需连续提醒可多次点击按钮累计额度',
      '收到通知后可直接从服务通知跳回小程序处理事件'
    ]
  },

  handleSubscribe() {
    if (this.data.loading) {
      return;
    }
    const templateIds = SUBSCRIBE_TEMPLATE_IDS.filter((id) => !!id);
    if (!templateIds.length) {
      wx.showToast({ title: '未配置订阅模板', icon: 'none' });
      return;
    }
    this.setData({ loading: true });
    wx.requestSubscribeMessage({
      tmplIds: templateIds,
      success: (res) => {
        const formatted = this.formatResult(res);
        this.setData({ lastSubscribeStatus: formatted });
        const accepted = this.countAccepted(res);
        if (accepted > 0) {
          this.syncAuthorization(res);
        } else {
          wx.showToast({ title: '未授予消息推送', icon: 'none' });
        }
      },
      fail: (err) => {
        console.warn('订阅失败', err);
        wx.showToast({ title: '订阅失败，请重试', icon: 'none' });
      },
      complete: () => {
        this.setData({ loading: false });
      }
    });
  },

  countAccepted(result = {}) {
    if (!result) {
      return 0;
    }
    let count = 0;
    Object.keys(result).forEach((key) => {
      if (result[key] === 'accept') {
        count += 1;
      }
    });
    return count;
  },

  formatResult(result = {}) {
    if (!result || !Object.keys(result).length) {
      return '尚未授权';
    }
    return Object.entries(result)
      .map(([templateId, status]) => `${templateId}：${status}`)
      .join('\n');
  },

  syncAuthorization(result) {
    wx.login({
      success: ({ code }) => {
        if (!code) {
          wx.showToast({ title: '获取登录态失败', icon: 'none' });
          return;
        }
        wx.request({
          url: REPORT_ENDPOINT,
          method: 'POST',
          header: { 'content-type': 'application/json' },
          data: {
            code,
            results: result,
            scene: REPORT_SCENE
          },
          success: ({ data }) => {
            if (data?.ok) {
              wx.showToast({ title: '授权已记录', icon: 'success' });
            } else {
              wx.showToast({ title: data?.message || '上报失败', icon: 'none' });
            }
          },
          fail: (err) => {
            console.warn('上报订阅失败', err);
            wx.showToast({ title: '上报订阅失败', icon: 'none' });
          }
        });
      },
      fail: (err) => {
        console.warn('wx.login 失败', err);
        wx.showToast({ title: '登录态失效，请重试', icon: 'none' });
      }
    });
  },

  simulateAlert() {
    wx.showModal({
      title: '通知示例',
      content: '收到真实报警时，会以“服务通知”的形式推送到您的微信，点击通知即可回到小程序处理。',
      showCancel: false,
      confirmText: '知道了'
    });
  }
});

