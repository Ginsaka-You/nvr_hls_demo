const app = getApp();
const globalData = (app && app.globalData) || {};
const USER_ID = globalData.defaultUserId || "demo-admin";
const TEMPLATE_ID =
  globalData.templateId || "lKtnQJIVx4mFSs9QJWm6xhL5in6KYOsrcdmhpNzCLfQ";

Page({
  data: {
    userId: USER_ID
  },
  async onSubscribeTap() {
    wx.requestSubscribeMessage({
      tmplIds: [TEMPLATE_ID],
      success: async (res) => {
        try {
          await wx.cloud.callFunction({
            name: "bindUser",
            data: { userId: USER_ID }
          });
          const accepted = res && res[TEMPLATE_ID] === "accept";
          wx.showToast({
            title: accepted ? "订阅与账号绑定已更新" : "账号绑定已更新",
            icon: "success"
          });
        } catch (err) {
          console.error("bindUser failed", err);
          wx.showToast({ title: "绑定失败", icon: "none" });
        }
      },
      fail: (err) => {
        console.error("subscribe fail", err);
        wx.showToast({ title: "订阅失败", icon: "none" });
      }
    });
  }
});
