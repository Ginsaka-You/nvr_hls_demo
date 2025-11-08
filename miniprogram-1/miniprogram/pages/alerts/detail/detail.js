const app = getApp();

Page({
  data: {
    alert: null,
    loading: true,
    note: '',
    imageUrl: ''
  },
  async onLoad(options) {
    this.id = options.id;
    this.db = wx.cloud.database();
    await this.loadAlert();
  },
  async loadAlert() {
    this.setData({ loading: true });
    try {
      const res = await this.db.collection('alarms').doc(this.id).get();
      const alert = res.data;
      let imageUrl = '';
      if (alert.snapshotFileId) {
        const temp = await wx.cloud.getTempFileURL({ fileList: [alert.snapshotFileId] });
        imageUrl = temp.fileList[0]?.tempFileURL || '';
      }
      this.setData({
        alert: {
          ...alert,
          occurAtFmt: this.formatTime(alert.occurAt),
          handledAtFmt: alert.handledAt ? this.formatTime(alert.handledAt) : ''
        },
        imageUrl,
        loading: false
      });
    } catch (err) {
      console.error('load alert fail', err);
      wx.showToast({ title: '加载失败', icon: 'none' });
      this.setData({ loading: false });
    }
  },
  onNoteInput(e) {
    this.setData({ note: e.detail.value });
  },
  async handleAck() {
    if (!this.data.alert || this.data.alert.status === 'ACK') {
      wx.showToast({ title: '已处理', icon: 'none' });
      return;
    }
    const confirm = await wx.showModal({
      title: '确认处理',
      content: '确认将该告警标记为已处理？',
      showCancel: true
    });
    if (!confirm.confirm) {
      return;
    }
    wx.showLoading({ title: '提交中', mask: true });
    try {
      await wx.cloud.callFunction({
        name: 'ackAlarm',
        data: { id: this.id, note: this.data.note }
      });
      wx.hideLoading();
      wx.showToast({ title: '已处理', icon: 'success' });
      this.loadAlert();
    } catch (err) {
      wx.hideLoading();
      console.error('ack fail', err);
      wx.showToast({ title: '提交失败', icon: 'none' });
    }
  },
  formatTime(value) {
    if (!value) return '';
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return `${date.getFullYear()}-${(date.getMonth() + 1).toString().padStart(2, '0')}-${date
      .getDate()
      .toString()
      .padStart(2, '0')} ${date.getHours().toString().padStart(2, '0')}:${date
      .getMinutes()
      .toString()
      .padStart(2, '0')}`;
  }
});
