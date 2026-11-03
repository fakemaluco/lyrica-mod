alter table public.music_presence_tracks drop constraint music_presence_tracks_cover_url_check;
alter table public.music_presence_tracks add constraint music_presence_tracks_cover_url_check check (length(cover_url) <= 32768);
do $$ declare name text; begin
 foreach name in array array['music_presence_rooms', 'music_presence_challenges', 'music_presence_sessions', 'music_presence_tracks', 'music_presence_limits'] loop
   execute format('create policy no_direct_client_access on public.%I for all to anon, authenticated using (false) with check (false)', name);
 end loop;
end $$;
