// Supabase Edge Function: push
// بتصحّي جهاز المستلم عن طريق FCM (مجاني). الإشعار مفيهوش أي محتوى: بس {kind: "msg" | "call"}.
//
// الأسرار: FIREBASE_SERVICE_ACCOUNT = محتوى ملف service account JSON بتاع Firebase (كامل).
// الدالة بتتحقق من JWT المستخدم، وبترفض لو مفيش رسالة/مكالمة حقيقية اتبعتت للمستلم دلوقتي (منع السبام).

import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const SA = JSON.parse(Deno.env.get("FIREBASE_SERVICE_ACCOUNT") ?? "{}");
const admin = createClient(SUPABASE_URL, SERVICE_KEY, { auth: { persistSession: false } });

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};
const json = (b: unknown, status = 200) =>
  new Response(JSON.stringify(b), { status, headers: { ...cors, "Content-Type": "application/json" } });

const b64url = (data: Uint8Array | string) => {
  const bytes = typeof data === "string" ? new TextEncoder().encode(data) : data;
  let s = "";
  bytes.forEach((b) => (s += String.fromCharCode(b)));
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

let cached: { token: string; exp: number } | null = null;

async function googleAccessToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  if (cached && cached.exp - 60 > now) return cached.token;

  const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const claim = b64url(JSON.stringify({
    iss: SA.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const pem = (SA.private_key as string).replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  const der = Uint8Array.from(atob(pem), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claim}`)));
  const assertion = `${header}.${claim}.${b64url(sig)}`;

  const res = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion }),
  });
  const j = await res.json();
  if (!j.access_token) throw new Error("google auth failed: " + JSON.stringify(j));
  cached = { token: j.access_token, exp: now + (j.expires_in ?? 3600) };
  return cached.token;
}

async function sendFcm(token: string, data: Record<string, string>, ttl: string, access: string) {
  return await fetch(`https://fcm.googleapis.com/v1/projects/${SA.project_id}/messages:send`, {
    method: "POST",
    headers: { Authorization: `Bearer ${access}`, "Content-Type": "application/json" },
    body: JSON.stringify({ message: { token, data, android: { priority: "HIGH", ttl } } }),
  });
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
  try {
    if (!SA.project_id) return json({ error: "FIREBASE_SERVICE_ACCOUNT not configured" }, 503);

    const jwt = req.headers.get("Authorization")?.replace("Bearer ", "");
    if (!jwt) return json({ error: "unauthorized" }, 401);
    const { data: u, error: uerr } = await admin.auth.getUser(jwt);
    if (uerr || !u.user) return json({ error: "unauthorized" }, 401);
    const caller = u.user.id;

    const { to, to_many, kind, call_id, video } = await req.json();
    if (kind !== "msg" && kind !== "call") return json({ error: "bad request" }, 400);

    // رسايل المجموعات: نداء واحد لكل الأعضاء بدل نداء لكل عضو (بيوفّر داتا على جهاز المرسل)
    const targets: string[] = kind === "msg" && Array.isArray(to_many)
      ? to_many.filter((x: unknown) => typeof x === "string").slice(0, 50)
      : typeof to === "string" ? [to] : [];
    if (!targets.length) return json({ error: "bad request" }, 400);

    // لازم يكون في حدث حقيقي من المتصل للمستلم في آخر دقيقة (منع السبام)
    const since = new Date(Date.now() - 60_000).toISOString();
    let allowed: string[] = [];
    if (kind === "call") {
      const { data } = await admin.from("call_signals").select("id").eq("from_id", caller).eq("to_id", targets[0])
        .eq("call_id", call_id).eq("type", "offer").gt("created_at", since).limit(1);
      if (!data?.length) return json({ error: "no recent call" }, 403);
      allowed = [targets[0]];
    } else {
      const { data } = await admin.from("mailbox").select("receiver_id").eq("sender_id", caller)
        .in("receiver_id", targets).gt("created_at", since);
      allowed = [...new Set((data ?? []).map((r: { receiver_id: string }) => r.receiver_id))];
      if (!allowed.length) return json({ error: "no recent message" }, 403);
    }

    const { data: rows } = await admin.from("push_tokens").select("user_id,token").in("user_id", allowed);
    if (!rows?.length) return json({ sent: 0, reason: "no token" });

    const access = await googleAccessToken();
    const ttl = kind === "call" ? "45s" : "86400s";
    let sent = 0;
    await Promise.all(rows.map(async (r: { user_id: string; token: string }) => {
      const res = await sendFcm(r.token, { kind, video: String(!!video) }, ttl, access);
      if (res.ok) { sent++; return; }
      const t = await res.text();
      // token مات (التطبيق اتمسح مثلًا): امسحه
      if (res.status === 404 || t.includes("UNREGISTERED")) await admin.from("push_tokens").delete().eq("user_id", r.user_id);
    }));
    return json({ sent });
  } catch (e) {
    return json({ error: String(e) }, 500);
  }
});
