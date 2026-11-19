create table public.music_listen_shares (
  account_id uuid primary key,
  token_hash text not null references public.music_presence_sessions on delete cascade,
  room_id uuid not null references public.music_presence_rooms on delete cascade,
  player_id uuid not null,
  player_name text not null,
  invite_hash text not null unique,
  song jsonb,
  queue jsonb not null,
  updated_at timestamptz not null,
  expires_at timestamptz not null
);
create index music_listen_shares_room_player on public.music_listen_shares(room_id, player_id);
create index music_listen_shares_token on public.music_listen_shares(token_hash);
create index music_listen_shares_expiry on public.music_listen_shares(expires_at);

alter table public.music_listen_shares enable row level security;
revoke all on public.music_listen_shares from public, anon, authenticated;
grant select, insert, update, delete on public.music_listen_shares to service_role;

create function public.music_listen_sync(p_token_hash text, p_invite_hash text, p_revoke boolean,
 p_song jsonb, p_queue jsonb, p_target_hash text, p_target_player uuid) returns jsonb
language plpgsql security invoker set search_path = '' as $$
declare s public.music_presence_sessions; host jsonb;
begin
 select * into s from public.music_presence_sessions where token_hash = p_token_hash and expires_at > now();
 if not found then raise exception 'invalid_session'; end if;
 perform public.music_presence_limit('listen:' || p_token_hash, 60);

 if s.can_publish and p_revoke then
   delete from public.music_listen_shares where token_hash = s.token_hash;
 elsif s.can_publish and p_invite_hash is not null then
   insert into public.music_listen_shares(account_id, token_hash, room_id, player_id, player_name,
    invite_hash, song, queue, updated_at, expires_at)
   values(s.account_id, s.token_hash, s.room_id, s.player_id, s.player_name,
    p_invite_hash, p_song, coalesce(p_queue, '[]'::jsonb), now(), now() + interval '25 seconds')
   on conflict(account_id) do update set token_hash = excluded.token_hash, room_id = excluded.room_id,
    player_id = excluded.player_id, player_name = excluded.player_name, invite_hash = excluded.invite_hash,
    song = excluded.song, queue = excluded.queue, updated_at = excluded.updated_at, expires_at = excluded.expires_at;
 end if;

 if p_target_hash is not null then
   select jsonb_build_object('id', x.account_id, 'name', x.player_name, 'song', x.song, 'queue', x.queue,
       'age_ms', floor(extract(epoch from now() - x.updated_at) * 1000)) into host
   from public.music_listen_shares x
   where x.invite_hash = p_target_hash and x.account_id <> s.account_id and x.expires_at > now();
 elsif p_target_player is not null and s.can_publish then
   select jsonb_build_object('id', x.account_id, 'name', x.player_name, 'song', x.song, 'queue', x.queue,
       'age_ms', floor(extract(epoch from now() - x.updated_at) * 1000)) into host
   from public.music_listen_shares x
   where x.room_id = s.room_id and x.player_id = p_target_player and x.account_id <> s.account_id
     and x.expires_at > now()
   order by x.updated_at desc limit 1;
 end if;

 return jsonb_build_object('can_share', s.can_publish, 'host', host);
end $$;

revoke all on function public.music_listen_sync(text, text, boolean, jsonb, jsonb, text, uuid)
 from public, anon, authenticated;
grant execute on function public.music_listen_sync(text, text, boolean, jsonb, jsonb, text, uuid) to service_role;

select cron.schedule('lyrica-listen-expiry', '* * * * *', $job$
 delete from public.music_listen_shares where expires_at <= now();
$job$);
