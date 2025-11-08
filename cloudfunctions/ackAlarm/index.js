"use strict";

const cloud = require("wx-server-sdk");
const app = cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });
const db = app.database();
const alarmsCollection = db.collection("alarms");

exports.main = async (event, context) => {
  const { OPENID } = context;
  if (!OPENID) {
    return { code: 401, message: "unauthorized" };
  }
  const { id, note = "" } = event || {};
  if (!id) {
    return { code: 400, message: "missing id" };
  }
  try {
    await alarmsCollection.doc(id).update({
      status: "ACK",
      handledBy: OPENID,
      handledAt: Date.now(),
      note
    });
    return { code: 0, ok: true };
  } catch (err) {
    console.error("[ackAlarm] update failed", err);
    return { code: 500, message: "update failed" };
  }
};
