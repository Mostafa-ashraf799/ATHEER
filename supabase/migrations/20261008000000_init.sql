-- ChatApp: السكيما الكاملة (الحسابات، الستوريز، المكالمات، الصندوق الاحتياطي المشفّر، الإشعارات)
-- الرسايل نفسها مش بتتخزن هنا: بتروح P2P وبتتخزن محليًا على الأجهزة.

create table if not exists public.profiles (
  id             uuid primary key references auth.users(id) on delete cascade,
  phone          text unique not null,
  display_name   text not null default '',
  about          text not null default 'متاح',
  avatar_file_id text,
  public_key     text,
  tg_user_id     bigint unique,
  settings       jsonb not null default '{}'::jsonb,
  last_seen      timestamptz default now(),
  created_at     timestamptz not null default now()
);

create table if not exists public.push_tokens (
  user_id    uuid primary key references public.profiles(id) on delete cascade,
  token      text not null,
  updated_at timestamptz not null default now()
);

create table if not exists public.stories (
  id          bigint generated always as identity primary key,
  user_id     uuid not null references public.profiles(id) on delete cascade,
  file_id     text not null,
  media_type  text not null default 'image' check (media_type in ('image','video')),
  caption     text,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null default (now() + interval '24 hours')
);
create index if not exists stories_user_idx on public.stories (user_id, created_at desc);

create table if not exists public.story_views (
  story_id  bigint references public.stories(id) on delete cascade,
  viewer_id uuid references public.profiles(id) on delete cascade,
  viewed_at timestamptz not null default now(),
  primary key (story_id, viewer_id)
);

-- إشارات WebRTC (SDP/ICE) للمكالمات واتصال الشات P2P. الميديا والرسايل نفسها مش بتعدّي هنا.
create index if not exists story_views_viewer_idx on public.story_views (viewer_id);

create table if not exists public.call_signals (
  id         bigint generated always as identity primary key,
  call_id    uuid not null,
  from_id    uuid not null references public.profiles(id) on delete cascade,
  to_id      uuid not null references public.profiles(id) on delete cascade,
  type       text not null check (type in ('offer','answer','ice','hangup','reject','hello')),
  purpose    text not null default 'call' check (purpose in ('call','chat')),
  video      boolean not null default false,
  payload    jsonb not null default '{}'::jsonb,
  created_at timestamptz not null default now()
);
create index if not exists call_signals_to_idx on public.call_signals (to_id, created_at desc);

create index if not exists call_signals_from_idx on public.call_signals (from_id);

create table if not exists public.call_logs (
  id         uuid primary key,
  caller_id  uuid not null references public.profiles(id) on delete cascade,
  callee_id  uuid not null references public.profiles(id) on delete cascade,
  video      boolean not null default false,
  status     text not null default 'ringing' check (status in ('ringing','answered','missed','rejected','ended')),
  started_at timestamptz not null default now(),
  duration_s int not null default 0
);

create index if not exists call_logs_caller_idx on public.call_logs (caller_id);
create index if not exists call_logs_callee_idx on public.call_logs (callee_id);

-- صندوق مؤقت لرسايل مشفّرة (ciphertext) لما الطرف التاني أوفلاين. بيتمسح أول ما يسحبها.
create table if not exists public.mailbox (
  id          bigint generated always as identity primary key,
  sender_id   uuid not null references public.profiles(id) on delete cascade,
  receiver_id uuid not null references public.profiles(id) on delete cascade,
  msg_id      uuid not null,
  ciphertext  text not null,
  created_at  timestamptz not null default now()
);
create index if not exists mailbox_receiver_idx on public.mailbox (receiver_id, created_at);

create index if not exists mailbox_sender_idx on public.mailbox (sender_id);

-- طلبات الدخول عن طريق بوت تيليجرام (الـ service role بس، مفيش policies)
create table if not exists public.tg_login_requests (
  token       text primary key,
  status      text not null default 'pending' check (status in ('pending','approved','consumed')),
  phone       text,
  tg_user_id  bigint,
  tg_name     text,
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null default (now() + interval '5 minutes')
);

-- ---------------- بروفايل تلقائي عند إنشاء الحساب ----------------
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = public as $$
begin
  insert into public.profiles (id, phone)
  values (new.id, coalesce(new.raw_user_meta_data->>'phone', new.phone, new.id::text))
  on conflict (id) do nothing;
  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
  after insert on auth.users
  for each row execute function public.handle_new_user();

-- ---------------- RLS ----------------
alter table public.profiles         enable row level security;
alter table public.push_tokens      enable row level security;
alter table public.stories          enable row level security;
alter table public.story_views      enable row level security;
alter table public.call_signals     enable row level security;
alter table public.call_logs        enable row level security;
alter table public.mailbox          enable row level security;
alter table public.tg_login_requests enable row level security;

create policy "profiles_select" on public.profiles for select to authenticated using (true);
create policy "profiles_update_own" on public.profiles for update to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));

-- المستخدم يعدّل بس الأعمدة دي؛ الرقم و tg_user_id من السيرفر فقط (منع انتحال الأرقام)
revoke update on public.profiles from authenticated, anon;
grant update (display_name, about, avatar_file_id, settings, last_seen, public_key)
  on public.profiles to authenticated;

create policy "push_tokens_own_select" on public.push_tokens for select to authenticated
  using (user_id = (select auth.uid()));
create policy "push_tokens_own_insert" on public.push_tokens for insert to authenticated
  with check (user_id = (select auth.uid()));
create policy "push_tokens_own_update" on public.push_tokens for update to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

create policy "stories_select" on public.stories for select to authenticated using (expires_at > now());
create policy "stories_insert" on public.stories for insert to authenticated
  with check (user_id = (select auth.uid()));
create policy "stories_delete_own" on public.stories for delete to authenticated
  using (user_id = (select auth.uid()));

create policy "story_views_insert" on public.story_views for insert to authenticated
  with check (viewer_id = (select auth.uid()));
create policy "story_views_select" on public.story_views for select to authenticated
  using (viewer_id = (select auth.uid())
         or exists (select 1 from public.stories s where s.id = story_id and s.user_id = (select auth.uid())));

create policy "signals_select" on public.call_signals for select to authenticated
  using (to_id = (select auth.uid()) or from_id = (select auth.uid()));
create policy "signals_insert" on public.call_signals for insert to authenticated
  with check (from_id = (select auth.uid()));

create policy "calllogs_select" on public.call_logs for select to authenticated
  using (caller_id = (select auth.uid()) or callee_id = (select auth.uid()));
create policy "calllogs_insert" on public.call_logs for insert to authenticated
  with check (caller_id = (select auth.uid()));
create policy "calllogs_update" on public.call_logs for update to authenticated
  using (caller_id = (select auth.uid()) or callee_id = (select auth.uid()));

create policy "mailbox_insert" on public.mailbox for insert to authenticated
  with check (sender_id = (select auth.uid()));
create policy "mailbox_select_receiver" on public.mailbox for select to authenticated
  using (receiver_id = (select auth.uid()));
create policy "mailbox_delete_receiver" on public.mailbox for delete to authenticated
  using (receiver_id = (select auth.uid()));

-- ---------------- Realtime ----------------
alter publication supabase_realtime add table public.call_signals;
alter publication supabase_realtime add table public.mailbox;
alter publication supabase_realtime add table public.stories;
