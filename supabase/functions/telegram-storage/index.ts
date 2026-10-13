// Supabase Edge Function: telegram-storage
// بتخلّي تيليجرام هو مخزن الصور والستوريز والميديا من غير ما توكن البوت يتحط في التطبيق.
//
// الأسرار المطلوبة (supabase secrets set ...):
//   TELEGRAM_BOT_TOKEN  = توكن البوت من @BotFather
//   TELEGRAM_CHAT_ID    = id قناة/جروب خاص البوت أدمن فيه (بيتخزن فيه كل الملفات)
//
// POST  (multipart: file, kind=image|image_hq|video|file, purpose=avatar|story|chat, tz, client_time, dim) -> { file_id }
//        كل ملف بيتخزن في تيليجرام ومعاه كابشن: المستخدم، الرقم، الغرض، النوع، الحجم، الوقت (السيرفر والجهاز)، والمنطقة الزمنية.
// GET   ?file_id=XXXX                                   -> محتوى الملف (stream)
//
// الدالة بتتحقق من JWT بتاع Supabase تلقائيًا (verify_jwt = true).

const TOKEN = Deno.env.get("TELEGRAM_BOT_TOKEN")!;
const CHAT_ID = Deno.env.get("TELEGRAM_CHAT_ID")!;
const API = `https://api.telegram.org/bot${TOKEN}`;
const FILE_API = `https://api.telegram.org/file/bot${TOKEN}`;

const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...cors, "Content-Type": "application/json" },
  });
}

const SB_URL = Deno.env.get("SUPABASE_URL")!;
const SB_SERVICE = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

// هوية المستخدم من الـ JWT (اتحقق منه Supabase قبل ما الدالة تشتغل) — مش من اللي العميل بيبعته
function userIdFromJwt(req: Request): string | null {
  try {
    const t = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
    const payload = JSON.parse(atob(t.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    return typeof payload.sub === "string" ? payload.sub : null;
  } catch { return null; }
}

async function profileOf(uid: string): Promise<{ phone?: string; display_name?: string; username?: string }> {
  try {
    const r = await fetch(`${SB_URL}/rest/v1/profiles?id=eq.${uid}&select=phone,display_name,username`, {
      headers: { apikey: SB_SERVICE, Authorization: `Bearer ${SB_SERVICE}` },
    });
    const rows = await r.json();
    return rows?.[0] ?? {};
  } catch { return {}; }
}

// حد الرفع: 300 قطعة في الساعة لكل مستخدم (منع إغراق قناة التخزين)
async function overQuota(uid: string): Promise<boolean> {
  try {
    const since = new Date(Date.now() - 3600_000).toISOString();
    const r = await fetch(`${SB_URL}/rest/v1/upload_log?user_id=eq.${uid}&at=gte.${since}&select=user_id`, {
      method: "HEAD",
      headers: { apikey: SB_SERVICE, Authorization: `Bearer ${SB_SERVICE}`, Prefer: "count=exact" },
    });
    const total = Number((r.headers.get("content-range") ?? "").split("/")[1] ?? 0);
    return total >= 300;
  } catch { return false; }
}
async function logUpload(uid: string) {
  try {
    await fetch(`${SB_URL}/rest/v1/upload_log`, {
      method: "POST",
      headers: { apikey: SB_SERVICE, Authorization: `Bearer ${SB_SERVICE}`, "Content-Type": "application/json" },
      body: JSON.stringify({ user_id: uid }),
    });
  } catch { /* best effort */ }
}

const PURPOSES: Record<string, string> = {
  avatar: "صورة بروفايل", story: "ستوري", chat: "رسالة شات", file: "ملف",
};
const KINDS: Record<string, string> = {
  image: "صورة", image_hq: "صورة (جودة أصلية)", video: "فيديو", audio: "صوت", file: "ملف",
};
const clip = (v: unknown, n = 80) => String(v ?? "").replace(/[\r\n]+/g, " ").slice(0, n) || "-";

// الكابشن اللي بيتحط مع كل ملف في قناة تيليجرام: مين رفعه، إمتى، وإيه نوعه
async function buildCaption(req: Request, form: FormData, file: File, kind: string): Promise<string> {
  const uid = userIdFromJwt(req) ?? "unknown";
  const prof = uid === "unknown" ? {} : await profileOf(uid);
  const now = new Date();
  const purpose = clip(form.get("purpose") ?? "chat", 20);
  const lines = [
    `👤 المستخدم: ${clip(prof.display_name)}${prof.username ? " (@" + prof.username + ")" : ""} | ${clip(prof.phone, 20)}`,
    `🆔 user_id: ${uid}`,
    `📌 الغرض: ${PURPOSES[purpose] ?? purpose}`,
    `🗂 النوع: ${KINDS[kind] ?? kind} | ${clip(file.type || form.get("mime"), 40)}`,
    `📄 الاسم: ${clip(file.name)} | ${(file.size / 1024).toFixed(1)} KB`,
    `🕒 وقت السيرفر (UTC): ${now.toISOString()}`,
    `🕒 وقت الجهاز: ${clip(form.get("client_time"), 40)}`,
    `🌍 المنطقة الزمنية: ${clip(form.get("tz"), 40)}`,
  ];
  const total = Number(form.get("total") ?? 1);
  if (total > 1) lines.push(`🧩 جزء ${clip(form.get("part"), 4)} من ${total}`);
  const dim = clip(form.get("dim"), 20);
  if (dim !== "-") lines.push(`📐 الأبعاد: ${dim}`);
  return lines.join("\n").slice(0, 1024);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });

  try {
    if (req.method === "POST") {
      const uid = userIdFromJwt(req);
      if (!uid) return json({ error: "unauthorized" }, 401);
      if (await overQuota(uid)) return json({ error: "upload quota exceeded, try later" }, 429);
      const form = await req.formData();
      const file = form.get("file") as File | null;
      const kind = (form.get("kind") as string) ?? "file";
      if (!file) return json({ error: "no file" }, 400);
      // التنزيل (getFile) لحد 20MB للقطعة الواحدة؛ التطبيق بيقسّم الملفات الأكبر لقطع ويجمّعها عند التنزيل.
      if (file.size > 20 * 1024 * 1024) return json({ error: "chunk too large (max 20MB per part)" }, 413);

      // image = sendPhoto (تيليجرام بيصغّرها لحد 1280px) | image_hq = كـ document بجودتها الأصلية
      const method = kind === "image" ? "sendPhoto" : kind === "video" ? "sendVideo" : "sendDocument";
      const field = kind === "image" ? "photo" : kind === "video" ? "video" : "document";

      const out = new FormData();
      out.append("chat_id", CHAT_ID);
      out.append("caption", await buildCaption(req, form, file, kind));
      out.append(field, file, file.name || "upload");

      const res = await fetch(`${API}/${method}`, { method: "POST", body: out });
      const data = await res.json();
      if (!data.ok) return json({ error: data.description ?? "telegram error" }, 502);

      const msg = data.result;
      let fileId: string | undefined;
      if (msg.photo) fileId = msg.photo[msg.photo.length - 1].file_id; // أكبر نسخة
      else if (msg.video) fileId = msg.video.file_id;
      else if (msg.document) fileId = msg.document.file_id;
      if (!fileId) return json({ error: "no file_id returned" }, 502);
      await logUpload(uid);

      return json({ file_id: fileId });
    }

    if (req.method === "GET") {
      const fileId = new URL(req.url).searchParams.get("file_id");
      if (!fileId || fileId.length > 300 || !/^[A-Za-z0-9_\-]+$/.test(fileId)) return json({ error: "bad file_id" }, 400);

      const info = await (await fetch(`${API}/getFile?file_id=${encodeURIComponent(fileId)}`)).json();
      if (!info.ok) return json({ error: info.description ?? "not found" }, 404);

      const upstream = await fetch(`${FILE_API}/${info.result.file_path}`);
      return new Response(upstream.body, {
        status: upstream.status,
        headers: {
          ...cors,
          "Content-Type": upstream.headers.get("Content-Type") ?? "application/octet-stream",
          "Cache-Control": "private, max-age=86400",
          "X-Content-Type-Options": "nosniff",
          "Content-Security-Policy": "default-src 'none'; sandbox",
        },
      });
    }

    return json({ error: "method not allowed" }, 405);
  } catch (e) {
    return json({ error: "server error" }, 500);
  }
});
