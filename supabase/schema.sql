-- All data access goes through the authenticated Edge Function, never the public Data API.
create table public.music_presence_rooms (
  id uuid primary key default gen_random_uuid(),
  server_hash text not null unique check (server_hash ~ '^[0-9a-f]{64}$'),
  topic text not null unique default ('lyrica-music-' || gen_random_uuid()::text),
  created_at timestamptz not null default now()
);
create table public.music_presence_challenges (
  id uuid primary key default gen_random_uuid(),
  account_id uuid not null,
  player_id uuid not null,
  player_name text not null check (player_name ~ '^[A-Za-z0-9_]{1,16}$'),
  server_hash text not null check (server_hash ~ '^[0-9a-f]{64}$'),
  server_id text not null check (server_id ~ '^[0-9a-f]{40}$'),
  expires_at timestamptz not null default (now() + interval '90 seconds')
);
create table public.music_presence_sessions (
  token_hash text primary key check (token_hash ~ '^[0-9a-f]{64}$'),
  room_id uuid not null references public.music_presence_rooms on delete cascade,
  account_id uuid not null,
  player_id uuid not null,
  player_name text not null,
  expires_at timestamptz not null default (now() + interval '12 hours'),
  unique(room_id, account_id)
);
create index music_presence_sessions_expiry on public.music_presence_sessions(expires_at);
create table public.music_presence_tracks (
  room_id uuid not null references public.music_presence_rooms on delete cascade,
  player_id uuid not null,
  token_hash text not null references public.music_presence_sessions on delete cascade,
  player_name text not null,
  title text not null check (length(title) between 1 and 256),
  artist text not null check (length(artist) <= 256),
  cover_url text not null check (length(cover_url) <= 2048),
  expires_at timestamptz not null,
  primary key (room_id, player_id)
);
create index music_presence_tracks_token on public.music_presence_tracks(token_hash);
create index music_presence_tracks_expiry on public.music_presence_tracks(expires_at);
create table public.music_presence_limits (
  key text primary key,
  bucket timestamptz not null,
  hits integer not null
);

alter table public.music_presence_rooms enable row level security;
alter table public.music_presence_challenges enable row level security;
alter table public.music_presence_sessions enable row level security;
alter table public.music_presence_tracks enable row level security;
alter table public.music_presence_limits enable row level security;
revoke all on public.music_presence_rooms, public.music_presence_challenges,
 public.music_presence_sessions, public.music_presence_tracks, public.music_presence_limits from anon, authenticated;
grant all on public.music_presence_rooms, public.music_presence_challenges,
 public.music_presence_sessions, public.music_presence_tracks, public.music_presence_limits to service_role;

create function public.music_presence_limit(p_key text, p_max integer) returns void
language plpgsql security invoker set search_path = '' as $$
declare n integer;
begin
 insert into public.music_presence_limits as l values(p_key, date_trunc('minute', now()), 1)
 on conflict(key) do update set
 hits = case when l.bucket = date_trunc('minute', now()) then l.hits + 1 else 1 end,
 bucket = date_trunc('minute', now()) returning hits into n;
 if n > p_max then raise exception 'rate_limited'; end if;
end $$;

create function public.music_presence_open(p_account_id uuid, p_player_id uuid, p_player_name text,
 p_server_hash text, p_token_hash text) returns jsonb
language plpgsql security invoker set search_path = '' as $$
declare r public.music_presence_rooms;
begin
 insert into public.music_presence_rooms(server_hash) values(p_server_hash)
 on conflict(server_hash) do update set server_hash = excluded.server_hash returning * into r;
 delete from public.music_presence_sessions where room_id = r.id and account_id = p_account_id;
 insert into public.music_presence_sessions(token_hash, room_id, account_id, player_id, player_name)
 values(p_token_hash, r.id, p_account_id, p_player_id, p_player_name);
 return jsonb_build_object('topic', r.topic, 'expires_in_ms', 43200000);
end $$;

create function public.music_presence_sync(p_token_hash text, p_visible uuid[], p_write boolean,
 p_sharing boolean, p_title text, p_artist text, p_cover_url text) returns jsonb
language plpgsql security invoker set search_path = '' as $$
declare s public.music_presence_sessions; previous public.music_presence_tracks;
 changed boolean := false; peers jsonb; t text;
begin
 select * into s from public.music_presence_sessions where token_hash = p_token_hash and expires_at > now();
 if not found then raise exception 'invalid_session'; end if;
 perform public.music_presence_limit('sync:' || p_token_hash, 120);
 if coalesce(array_length(p_visible, 1), 0) > 512 then raise exception 'too_many_players'; end if;
 if p_write then
   select * into previous from public.music_presence_tracks where room_id = s.room_id and player_id = s.player_id;
   if p_sharing and length(p_title) > 0 then
     changed := previous.player_id is null or previous.title <> p_title or previous.artist <> p_artist
                or previous.cover_url <> p_cover_url or previous.expires_at <= now();
     insert into public.music_presence_tracks(room_id, player_id, token_hash, player_name, title, artist, cover_url, expires_at)
     values(s.room_id, s.player_id, s.token_hash, s.player_name, p_title, p_artist, p_cover_url, now() + interval '75 seconds')
     on conflict(room_id, player_id) do update set token_hash = excluded.token_hash, player_name = excluded.player_name,
       title = excluded.title, artist = excluded.artist, cover_url = excluded.cover_url, expires_at = excluded.expires_at;
   else
     delete from public.music_presence_tracks where room_id = s.room_id and player_id = s.player_id and token_hash = s.token_hash;
     changed := found;
   end if;
 end if;
 select coalesce(jsonb_agg(jsonb_build_object('player_id', x.player_id, 'player_name', x.player_name,
  'title', x.title, 'artist', x.artist, 'cover_url', x.cover_url,
  'expires_in_ms', floor(extract(epoch from (x.expires_at - now())) * 1000))), '[]'::jsonb) into peers
 from public.music_presence_tracks x where x.room_id = s.room_id and x.player_id = any(p_visible) and x.expires_at > now();
 select topic into t from public.music_presence_rooms where id = s.room_id;
 return jsonb_build_object('peers', peers, 'changed', changed, 'topic', t);
end $$;

create function public.music_presence_leave(p_token_hash text) returns jsonb
language plpgsql security invoker set search_path = '' as $$
declare t text;
begin
 select r.topic into t from public.music_presence_sessions s join public.music_presence_rooms r on r.id = s.room_id
 where s.token_hash = p_token_hash;
 delete from public.music_presence_sessions where token_hash = p_token_hash;
 return jsonb_build_object('topic', t);
end $$;

revoke all on function public.music_presence_limit(text, integer),
 public.music_presence_open(uuid, uuid, text, text, text),
 public.music_presence_sync(text, uuid[], boolean, boolean, text, text, text),
 public.music_presence_leave(text) from public, anon, authenticated;
grant execute on function public.music_presence_limit(text, integer),
 public.music_presence_open(uuid, uuid, text, text, text),
 public.music_presence_sync(text, uuid[], boolean, boolean, text, text, text),
 public.music_presence_leave(text) to service_role;

create extension if not exists pg_cron;
select cron.schedule('lyrica-presence-expiry', '* * * * *', $job$
 delete from public.music_presence_tracks where expires_at <= now();
 delete from public.music_presence_challenges where expires_at <= now();
 delete from public.music_presence_sessions where expires_at <= now();
 delete from public.music_presence_limits where bucket < now() - interval '5 minutes';
 delete from public.music_presence_rooms r where r.created_at < now() - interval '1 day'
   and not exists(select 1 from public.music_presence_sessions s where s.room_id = r.id);
$job$);

alter table public.music_presence_tracks drop constraint music_presence_tracks_cover_url_check;
alter table public.music_presence_tracks add constraint music_presence_tracks_cover_url_check check (length(cover_url) <= 32768);
do $$ declare name text; begin
 foreach name in array array['music_presence_rooms', 'music_presence_challenges', 'music_presence_sessions', 'music_presence_tracks', 'music_presence_limits'] loop
   execute format('create policy no_direct_client_access on public.%I for all to anon, authenticated using (false) with check (false)', name);
 end loop;
end $$;
