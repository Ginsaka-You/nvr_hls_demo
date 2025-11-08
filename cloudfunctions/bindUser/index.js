"use strict";

const cloud = require("wx-server-sdk");
const app = cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });
const db = app.database();
const usersCollection = db.collection("users");

exports.main = async (event, context) => {
  const { OPENID } = context;
  if (!OPENID) {
    return { code: 401, message: "unauthorized" };
  }
  const { userId } = event || {};
  if (!userId) {
    return { code: 400, message: "missing userId" };
  }
  try {
    await usersCollection.doc(userId).set({ openid: OPENID });
    return { code: 0, ok: true };
  } catch (err) {
    console.error("[bindUser] failed", err);
    return { code: 500, message: "bind failed" };
  }
};
