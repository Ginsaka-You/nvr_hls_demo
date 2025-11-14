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
    this.thumbnailCache = {};
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
          this.processSnapshot(snapshot).catch((err) => {
            console.error('process snapshot fail', err);
            this.setData({ error: '数据解析失败', loading: false });
          });
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
  async processSnapshot(snapshot) {
    const docs = snapshot?.docs || [];
    const alerts = [];
    for (const doc of docs) {
      alerts.push(await this.enrichAlert(doc));
    }
    this.setData({ alerts, loading: false, error: '' });
  },
  async enrichAlert(doc = {}) {
    const occurAt = doc.occurAt || doc.ts || doc.occurAtFmt;
    const severity = doc.severity || 'MEDIUM';
    const thumbnailUrl = await this.ensureThumbnail(doc.snapshotFileId);
    return {
      ...doc,
      title: doc.title || doc.eventType || '告警',
      severity,
      severityClass: this.toSeverityClass(severity),
      location: doc.location || doc.camera || doc.device || '未提供',
      device: doc.device || doc.camera || doc.eventId || '',
      displayTime: this.formatTime(occurAt),
      thumbnailUrl
    };
  },
  async ensureThumbnail(fileId) {
    if (!fileId) {
      return '';
    }
    const now = Date.now();
    const cached = this.thumbnailCache[fileId];
    if (cached && cached.expireAt > now + 60 * 1000) {
      return cached.url;
    }
    try {
      const res = await wx.cloud.getTempFileURL({ fileList: [fileId] });
      const fileInfo = res.fileList?.[0];
      if (fileInfo?.tempFileURL) {
        const maxAge = (fileInfo.maxAge || 3600) * 1000;
        this.thumbnailCache[fileId] = {
          url: fileInfo.tempFileURL,
          expireAt: now + maxAge
        };
        return fileInfo.tempFileURL;
      }
    } catch (err) {
      console.warn('get thumbnail fail', err);
    }
    return '';
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
    wx.navigateTo({ url: '/pages/settings/subscribe/subscribe' });
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
