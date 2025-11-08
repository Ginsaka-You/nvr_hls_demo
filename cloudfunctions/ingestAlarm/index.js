"use strict";

const cloud = require("wx-server-sdk");
const crypto = require("crypto");

const app = cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });
const db = app.database();
const alarmsCollection = db.collection("alarms");
const usersCollection = db.collection("users");
const storage = app.storage();

const SECRET = process.env.ALERT_INGEST_SECRET;
const TEMPLATE_ID = process.env.SUBSCRIBE_TEMPLATE_ID;

exports.main = async (event) => {
  if (!SECRET) {
    return buildJson(500, { message: "missing ALERT_INGEST_SECRET" });
  }
  try {
    const payload = parseRequestData(event);
    validateAlert(payload.alert);
    verifySignature(payload.alert, payload.sig);

    const fileId = await resolveSnapshotFileId(payload.alert);
    const doc = await persistAlert(payload.alert, fileId);
    await sendSubscribeIfPossible(doc._id, doc.userId, doc);

    return buildJson(200, { id: doc._id });
  } catch (err) {
    console.error("[ingestAlarm] failed", err);
    const status = err.statusCode || 500;
    return buildJson(status, { message: err.message || "internal error" });
  }
};

function parseRequestData(event) {
  if (!event || !event.request_data) {
    throw withStatus(400, "missing request_data");
  }
  try {
    return JSON.parse(event.request_data);
  } catch (err) {
    throw withStatus(400, "invalid request_data json");
  }
}

function validateAlert(alert) {
  const required = ["userId", "title", "severity", "location", "device", "occurAt"];
  required.forEach((field) => {
    if (!alert || !alert[field]) {
      throw withStatus(400, `missing field ${field}`);
    }
  });
}

function verifySignature(alert, sig) {
  if (!sig) {
    throw withStatus(401, "missing sig");
  }
  const expected = computeHmac(JSON.stringify(alert));
  if (sig !== expected) {
    throw withStatus(401, "signature mismatch");
  }
}

async function resolveSnapshotFileId(alert) {
  if (alert.imageBase64) {
    const buffer = Buffer.from(alert.imageBase64, "base64");
    const filePath = `alerts/${Date.now()}-${Math.random().toString(16).slice(2)}.jpg`;
    const res = await storage.uploadFile({
      cloudPath: filePath,
      fileContent: buffer
    });
    return res.fileID;
  }
  return alert.snapshotFileId || null;
}

async function persistAlert(alert, fileId) {
  const doc = {
    userId: alert.userId,
    title: alert.title,
    severity: alert.severity,
    location: alert.location,
    device: alert.device,
    camera: alert.camera || "",
    occurAt: alert.occurAt,
    snapshotFileId: fileId,
    status: "NEW",
    handledBy: "",
    handledAt: "",
    note: "",
    ts: Date.now()
  };
  const res = await alarmsCollection.add(doc);
  return { ...doc, _id: res.id };
}

async function sendSubscribeIfPossible(docId, userId, doc) {
  if (!TEMPLATE_ID) {
    console.warn("[ingestAlarm] missing SUBSCRIBE_TEMPLATE_ID");
    return;
  }
  const mapping = await usersCollection.doc(userId).get();
  if (!mapping.data || mapping.data.length === 0 || !mapping.data[0].openid) {
    console.warn("[ingestAlarm] no openid bound for user", userId);
    return;
  }
  const openid = mapping.data[0].openid;
  try {
    await cloud.openapi.subscribeMessage.send({
      touser: openid,
      templateId: TEMPLATE_ID,
      page: `pages/alerts/detail/detail?id=${docId}`,
      data: {
        thing2: { value: fitText(formatTitle(doc.title, doc.device)) },
        time4: { value: formatTimeCN(doc.occurAt) },
        thing8: { value: fitText(doc.cause || severityToText(doc.severity)) },
        thing11: { value: fitText(doc.location) }
      }
    });
  } catch (err) {
    console.error("[ingestAlarm] subscribe send error", err);
  }
}

function computeHmac(body) {
  return crypto.createHmac("sha256", SECRET).update(body).digest("hex");
}

function formatTitle(title, device) {
  if (device) {
    return `${title}（${device}）`;
  }
  return title;
}

function fitText(text = "", max = 20) {
  if (!text) {
    return "";
  }
  const arr = Array.from(text);
  return arr.length > max ? arr.slice(0, max).join("") : text;
}

function severityToText(severity) {
  const map = {
    CRITICAL: "紧急",
    HIGH: "高",
    MEDIUM: "中",
    LOW: "低"
  };
  return map[severity] || "低";
}

function formatTimeCN(iso) {
  if (!iso) {
    return "";
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const pad = (n) => n.toString().padStart(2, "0");
  return `${date.getFullYear()}年${pad(date.getMonth() + 1)}月${pad(date.getDate())}日 ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function buildJson(statusCode, data) {
  return {
    statusCode,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data)
  };
}

function withStatus(statusCode, message) {
  const err = new Error(message);
  err.statusCode = statusCode;
  return err;
}
