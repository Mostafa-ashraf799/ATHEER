// Supabase Edge Function: tg-auth
// تسجيل الدخول برقم الهاتف عن طريق بوت تيليجرام (مجاني، من غير SMS).
//
// الفكرة:
//  1) التطبيق:   POST {action:"start"}            -> {token, url}      (url = رابط البوت)
//  2) المستخدم يفتح الرابط ويدوس Start ثم "شارك رقمي".
//  3) تيليجرام -> الـ webhook (نفس الدالة) -> بنتأكد إن الرقم المشارَك بتاع نفس الحساب -> approved
//  4) التطبيق:   POST {action:"poll", token}      -> {status} أو {status:"ok", access_token, refresh_token}
//
// الأسرار (supabase secrets set ...):
//   TELEGRAM_BOT_TOKEN, TELEGRAM_BOT_USERNAME (من غير @),
//   TELEGRAM_WEBHOOK_SECRET (أي نص عشوائي طويل), AUTH_PW_SECRET (نص عشوائي طويل)
//
// تسجيل الـ webhook مرة واحدة:
//   curl "https://api.telegram.org/bot<TOKEN>/setWebhook" \
//     -d url="https://<PROJECT>.supabase.co/functions/v1/tg-auth" \
//     -d secret_token="<TELEGRAM_WEBHOOK_SECRET>"

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const BOT_TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN")!;
const BOT_USERNAME = Deno.env.get("TELEGRAM_BOT_USERNAME")!;
const WEBHOOK_SECRET = Deno.env.get("TELEGRAM_WEBHOOK_SECRET")!;
const PW_SECRET = Deno.env.get("AUTH_PW_SECRET")!;
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const ANON_KEY = Deno.env.get("SUPABASE_ANON_KEY")!;

const admin = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });
const anon = createClient(SUPABASE_URL, ANON_KEY, { auth: { persistSession: false } });
const TG = `https://api.telegram.org/bot${BOT_TOKEN}`;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};
const json = (b: unknown, status = 200) =>
  new Response(JSON.stringify(b), { status, headers: { ...cors, "Content-Type": "application/json" } });

async function tg(method: string, body: Record<string, unknown>) {
  await fetch(`${TG}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

function randomToken(bytes = 24) {
  const a = new Uint8Array(bytes);
  crypto.getRandomValues(a);
  return Array.from(a, (x) => x.toString(16).padStart(2, "0")).join("");
}

// باسورد داخلي مشتق من الرقم + سر السيرفر. المستخدم مش بيعرفه ومش بيستخدمه.
async function derivedPassword(phone: string) {
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(PW_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(phone));
  return Array.from(new Uint8Array(sig), (x) => x.toString(16).padStart(2, "0")).join("");
}

const normalizePhone = (p: string) => "+" + p.replace(/[^\d]/g, "");

// ------------------------------------------------------------ webhook من تيليجرام
async function handleWebhook(req: Request, update: any) {
  if (req.headers.get("x-telegram-bot-api-secret-token") !== WEBHOOK_SECRET) {
    return json({ error: "forbidden" }, 403);
  }
  const msg = update.message;
  if (!msg) return json({ ok: true });
  const chatId = msg.chat.id;

  // /start <token>
  if (typeof msg.text === "string" && msg.text.startsWith("/start")) {
    const token = msg.text.split(" ")[1]?.trim();
    if (!token) {
      await tg("sendMessage", { chat_id: chatId, text: "افتح التطبيق واضغط «الدخول عن طريق تيليجرام» علشان تبدأ." });
      return json({ ok: true });
    }
    const { data } = await admin.from("tg_login_requests").select("token,status,expires_at").eq("token", token).maybeSingle();
    if (!data || data.status !== "pending" || new Date(data.expires_at) < new Date()) {
      await tg("sendMessage", { chat_id: chatId, text: "الطلب ده منتهي. ارجع للتطبيق وجرّب تاني." });
      return json({ ok: true });
    }
    // نربط الطلب بحساب تيليجرام ده
    await admin.from("tg_login_requests").update({ tg_user_id: msg.from.id, tg_name: msg.from.first_name ?? null }).eq("token", token);
    await tg("sendMessage", {
      chat_id: chatId,
      text: "⚠️ اضغط الزرار بس لو إنت اللي فتحت التطبيق وبدأت تسجيل الدخول دلوقتي. لو حد بعتلك الرابط ده، تجاهله.\n\nاضغط الزرار تحت علشان تشارك رقمك وتأكّد حسابك 👇",
      reply_markup: {
        keyboard: [[{ text: "📱 شارك رقمي", request_contact: true }]],
        resize_keyboard: true, one_time_keyboard: true,
      },
    });
    return json({ ok: true });
  }

  // المستخدم شارك جهة اتصال
  if (msg.contact) {
    // لازم يكون رقمه هو (مش جهة اتصال لحد تاني)
    if (msg.contact.user_id !== msg.from.id) {
      await tg("sendMessage", { chat_id: chatId, text: "لازم تشارك رقمك انت من الزرار، مش رقم حد تاني." });
      return json({ ok: true });
    }
    const { data } = await admin
      .from("tg_login_requests")
      .select("token")
      .eq("tg_user_id", msg.from.id).eq("status", "pending")
      .gt("expires_at", new Date().toISOString())
      .order("created_at", { ascending: false }).limit(1).maybeSingle();
    if (!data) {
      await tg("sendMessage", { chat_id: chatId, text: "مفيش طلب دخول شغّال. ارجع للتطبيق وابدأ من جديد." });
      return json({ ok: true });
    }
    await admin.from("tg_login_requests")
      .update({ status: "approved", phone: normalizePhone(msg.contact.phone_number) })
      .eq("token", data.token);
    await tg("sendMessage", { chat_id: chatId, text: "تمام ✅ ارجع للتطبيق، هيدخلك لوحده.", reply_markup: { remove_keyboard: true } });
    return json({ ok: true });
  }

  return json({ ok: true });
}

// ------------------------------------------------------------ التطبيق
async function startLogin() {
  // تنضيف الطلبات المنتهية + سقف على الطلبات المعلّقة (منع إغراق الجدول من غير تسجيل دخول)
  await admin.from("tg_login_requests").delete().lt("expires_at", new Date().toISOString());
  const { count } = await admin.from("tg_login_requests").select("token", { count: "exact", head: true });
  if ((count ?? 0) > 5000) return json({ error: "busy, try again later" }, 429);
  const token = randomToken();
  const { error } = await admin.from("tg_login_requests").insert({ token });
  if (error) return json({ error: error.message }, 500);
  return json({ token, url: `https://t.me/${BOT_USERNAME}?start=${token}` });
}

async function pollLogin(token: string) {
  const { data } = await admin.from("tg_login_requests").select("*").eq("token", token).maybeSingle();
  if (!data || new Date(data.expires_at) < new Date()) return json({ status: "expired" });
  if (data.status === "pending") return json({ status: "pending" });
  if (data.status === "consumed") return json({ status: "expired" });

  // approved: جهّز/لاقي المستخدم وطلّع Session
  const phone: string = data.phone;
  const password = await derivedPassword(phone);
  // الحساب بيتعمل بإيميل داخلي مشتق من الرقم (من غير مزوّد SMS خالص)؛ الرقم الحقيقي في user_metadata
  const email = `${phone.replace(/\D/g, "")}@users.chatapp.invalid`;

  let res = await anon.auth.signInWithPassword({ email, password });
  if (res.error) {
    const { data: prof } = await admin.from("profiles").select("id").eq("phone", phone).maybeSingle();
    if (prof) {
      await admin.auth.admin.updateUserById(prof.id, { password, email, email_confirm: true });
    } else {
      const created = await admin.auth.admin.createUser({
        email, password, email_confirm: true, user_metadata: { phone },
      });
      if (created.error) return json({ error: created.error.message }, 500);
    }
    res = await anon.auth.signInWithPassword({ email, password });
  }
  if (res.error || !res.data.session) return json({ error: res.error?.message ?? "sign-in failed" }, 500);

  // أنشئ البروفايل لو مش موجود (من غير الاعتماد على trigger على auth.users)
  await admin.from("profiles").upsert({ id: res.data.user!.id, phone }, { onConflict: "id", ignoreDuplicates: true });

  // اربط الـ tg_user_id بالبروفايل (مرة واحدة) وخلّي الطلب مستهلَك
  await admin.from("profiles").update({ tg_user_id: data.tg_user_id }).eq("id", res.data.user!.id);
  const profile = await admin.from("profiles").select("display_name").eq("id", res.data.user!.id).maybeSingle();
  if (!profile.data?.display_name && data.tg_name) {
    await admin.from("profiles").update({ display_name: data.tg_name }).eq("id", res.data.user!.id);
  }
  await admin.from("tg_login_requests").update({ status: "consumed" }).eq("token", token);

  return json({
    status: "ok",
    access_token: res.data.session.access_token,
    refresh_token: res.data.session.refresh_token,
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
  try {
    const body = await req.json();
    if (body.update_id !== undefined) return await handleWebhook(req, body);
    if (body.action === "start") return await startLogin();
    if (body.action === "poll" && typeof body.token === "string") return await pollLogin(body.token);
    return json({ error: "bad request" }, 400);
  } catch (e) {
    return json({ error: "server error" }, 500);
  }
});
