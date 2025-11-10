'use strict';

const cloud = require('wx-server-sdk');
const crypto = require('crypto');

cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });        // ✅ 正确初始化
const db = cloud.database();
const alarms = db.collection('alarms');
const users  = db.collection('users');

const SECRET      = process.env.ALERT_INGEST_SECRET;
const TEMPLATE_ID = process.env.SUBSCRIBE_TEMPLATE_ID;

exports.main = async (event) => {
  try {
    assert(!!SECRET, 'missing ALERT_INGEST_SECRET');

    const { alert, sig } = parse(event);
    verifySig(alert, sig);

    const fileID = await resolveSnapshotFileId(alert);
    const id     = await persistAlert(alert, fileID);

    await trySendSubscribe(id, alert.userId, alert);
    return { id };                                    // ✅ 简单返回对象，OpenAPI 会放到 resp_data 里
  } catch (err) {
    console.error('[ingestAlarm] failed:', err);
    // 这里不要抛 HTTP 包装，直接返回错误字符串即可
    return { error: err.message || 'internal error' };
  }
};

// ---------- helpers ----------
function parse(event) {
  if (!event || !event.request_data) throw new Error('missing request_data');
  let body;
  try { body = JSON.parse(event.request_data); }
  catch { throw new Error('invalid request_data json'); }

  const alert = body.alert || {};
  const required = ['userId','title','severity','location','device','occurAt'];
  for (const k of required) if (!alert[k]) throw new Error('missing field ' + k);
  return { alert, sig: body.sig };
}

function verifySig(alert, sig) {
  if (!sig) throw new Error('missing sig');
  const expected = crypto.createHmac('sha256', SECRET)
                         .update(JSON.stringify(alert)).digest('hex');
  if (sig !== expected) throw new Error('signature mismatch');
}

async function resolveSnapshotFileId(alert) {
  if (alert.imageBase64) {
    const buffer    = Buffer.from(alert.imageBase64, 'base64');
    const cloudPath = `alerts/${Date.now()}-${Math.random().toString(16).slice(2)}.jpg`;
    const up = await cloud.uploadFile({ cloudPath, fileContent: buffer });   // ✅ 用 cloud.uploadFile
    return up.fileID;
  }
  return alert.snapshotFileId || null;
}

async function persistAlert(alert, fileID) {
  const doc = {
    userId: alert.userId,
    title: alert.title,
    severity: alert.severity,
    location: alert.location,
    device: alert.device,
    camera: alert.camera || '',
    occurAt: alert.occurAt,
    snapshotFileId: fileID,
    status: 'NEW',
    handledBy: '',
    handledAt: '',
    note: '',
    ts: Date.now()
  };
  const ret = await alarms.add({ data: doc });
  return ret._id || ret.id;            // 不同 SDK 版本有 _id 或 id
}

async function trySendSubscribe(docId, userId, alert) {
  if (!TEMPLATE_ID) {
    console.warn('[ingestAlarm] missing SUBSCRIBE_TEMPLATE_ID');
    return;
  }
  let openid = null;
  try {
    const u = await users.doc(userId).get();
    openid = u.data && u.data.openid;
  } catch(e) {
    console.warn('[ingestAlarm] no users mapping for', userId);
  }
  if (!openid) return;

  try {
    await cloud.openapi.subscribeMessage.send({
      touser: openid,
      templateId: TEMPLATE_ID,
      page: `pages/alerts/detail/detail?id=${docId}`,
      data: {
        thing2:  { value: cut20(`${alert.device || ''} ${alert.title || '设备异常'}`.trim()) },
        time4:   { value: fmtCN(alert.occurAt) },
        thing8:  { value: cut20(alert.cause || sevCN(alert.severity)) },
        thing11: { value: cut20(alert.location || '') }
      },
      miniprogramState: 'developer',
      lang: 'zh_CN'
    });
    console.log('[ingestAlarm] subscribe sent to', openid);
  } catch (e) {
    console.error('[ingestAlarm] subscribe send error', e);
  }
}

function cut20(s=''){ const arr = Array.from(String(s)); return arr.length>20?arr.slice(0,20).join(''):s; }
function fmtCN(iso){
  if(!iso) return '';
  const d=new Date(iso); if(isNaN(d)) return iso;
  const p=n=>String(n).padStart(2,'0');
  return `${d.getFullYear()}年${p(d.getMonth()+1)}月${p(d.getDate())}日 ${p(d.getHours())}:${p(d.getMinutes())}`;
}
function sevCN(s){ return {CRITICAL:'紧急',HIGH:'高',MEDIUM:'中',LOW:'低'}[s] || '异常'; }
function assert(ok,msg){ if(!ok) throw new Error(msg); }
