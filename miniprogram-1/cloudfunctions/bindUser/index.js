"use strict";

const cloud = require("wx-server-sdk");
cloud.init({ env: cloud.DYNAMIC_CURRENT_ENV });

exports.main = async (event) => {
  const { OPENID } = cloud.getWXContext();
  const db = cloud.database();
  const { userId } = event || {};
  if (!userId) {
    return { ok: false, error: "userId required" };
  }

  await db
    .collection("users")
    .doc(userId)
    .set({ data: { openid: OPENID, boundAt: Date.now() } });

  return { ok: true, userId, openid: OPENID };
};
