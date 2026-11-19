alter function public.lyrica_status_snapshot() rename to lyrica_status_metrics_snapshot;

create function public.lyrica_status_snapshot() returns jsonb
language sql stable security invoker set search_path = '' as $$
  select public.lyrica_status_metrics_snapshot() || jsonb_build_object(
    'presence_active', (
      select count(distinct s.account_id)
      from public.music_presence_limits l
      join public.music_presence_sessions s on s.token_hash = substring(l.key from 6)
      where l.key like 'sync:%'
        and l.bucket >= date_trunc('minute', now()) - interval '2 minutes'
        and s.expires_at > now()
        and s.can_publish
    )
  );
$$;

revoke all on function public.lyrica_status_snapshot() from public, anon, authenticated;
grant execute on function public.lyrica_status_snapshot() to service_role;
