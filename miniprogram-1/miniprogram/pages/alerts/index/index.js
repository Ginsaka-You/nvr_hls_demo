const app = getApp();

Page({
  data: {
    alerts: [],
    loading: true,
    error: ''
  },
  onLoad() {
    this.userId = app.globalData.defaultUserId || 'demo-admin';
    this.db = wx.cloud.database();
    this.startWatch();
  },
  onUnload() {
    this.stopWatch();
  },
  startWatch() {
    this.stopWatch();
    this.setData({ loading: true, error: '' });
    this.watcher = this.db
      .collection('alarms')
      .where({ userId: this.userId })
      .orderBy('ts', 'desc')
      .watch({
        onChange: (snapshot) => {
          const alerts = snapshot.docs.map((doc) => ({
            ...doc,
            displayTime: this.formatTime(doc.occurAt),
            severityClass: this.toSeverityClass(doc.severity)
          }));
          this.setData({ alerts, loading: false });
        },
        onError: (err) => {
          console.error('watch error', err);
          this.setData({ error: '实时连接中断，请下拉重试', loading: false });
        }
      });
  },
  stopWatch() {
    if (this.watcher) {
      this.watcher.close();
      this.watcher = null;
    }
  },
  onPullDownRefresh() {
    this.startWatch();
    wx.stopPullDownRefresh();
  },
  goDetail(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: `/pages/alerts/detail/detail?id=${id}` });
  },
  openSubscribe() {
    wx.navigateTo({ url: "/pages/settings/subscribe/subscribe" });
  },
  formatTime(value) {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    return `${date.getMonth() + 1}-${date.getDate()} ${date.getHours().toString().padStart(2, '0')}:${date
      .getMinutes()
      .toString()
      .padStart(2, '0')}`;
  },
  toSeverityClass(severity = 'LOW') {
    const map = {
      CRITICAL: 'badge-critical',
      HIGH: 'badge-high',
      MEDIUM: 'badge-medium',
      LOW: 'badge-low'
    };
    return map[severity] || 'badge-low';
  }
});
