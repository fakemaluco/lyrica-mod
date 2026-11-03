-- Unverified viewers receive short-lived, read-only sessions. They cannot claim a player identity.
alter table public.music_presence_sessions add column can_publish boolean not null default true;
create function public.music_presence_open_viewer(p_player_id uuid, p_server_hash text, p_token_hash text) returns jsonb
language plpgsql security invoker set search_path = '' as $$
declare r public.music_presence_rooms;
begin
 insert into public.music_presence_rooms(server_hash) values(p_server_hash)
 on conflict(server_hash) do update set server_hash = excluded.server_hash returning * into r;
 insert into public.music_presence_sessions(token_hash,room_id,account_id,player_id,player_name,expires_at,can_publish)
 values(p_token_hash,r.id,gen_random_uuid(),p_player_id,'Viewer',now()+interval '30 minutes',false);
 return jsonb_build_object('topic',r.topic,'expires_in_ms',1800000);
end $$;
revoke all on function public.music_presence_open_viewer(uuid,text,text) from public, anon, authenticated;
grant execute on function public.music_presence_open_viewer(uuid,text,text) to service_role;

create or replace function public.music_presence_sync(p_token_hash text, p_visible uuid[], p_write boolean,
 p_sharing boolean, p_title text, p_artist text, p_cover_url text) returns jsonb
language plpgsql security invoker set search_path = '' as $$
declare s public.music_presence_sessions; previous public.music_presence_tracks;
 changed boolean := false; peers jsonb; t text;
begin
 select * into s from public.music_presence_sessions where token_hash = p_token_hash and expires_at > now();
 if not found then raise exception 'invalid_session'; end if;
 perform public.music_presence_limit('sync:' || p_token_hash, 120);
 if coalesce(array_length(p_visible, 1), 0) > 512 then raise exception 'too_many_players'; end if;
 if p_write and s.can_publish then
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
