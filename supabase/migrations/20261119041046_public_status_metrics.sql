create table public.lyrica_status_clients (
  session_id uuid primary key,
  sequence integer not null,
  seen_at timestamptz not null
);
create index lyrica_status_clients_seen on public.lyrica_status_clients(seen_at);

create table public.lyrica_status_counters (
  bucket timestamptz not null,
  metric text not null,
  total bigint not null,
  primary key (bucket, metric)
);
create index lyrica_status_counter_metric on public.lyrica_status_counters(metric, bucket);

create table public.lyrica_status_servers (
  day date not null,
  network text not null,
  session_id uuid not null,
  primary key (day, network, session_id)
);

alter table public.lyrica_status_clients enable row level security;
alter table public.lyrica_status_counters enable row level security;
alter table public.lyrica_status_servers enable row level security;
revoke all on public.lyrica_status_clients, public.lyrica_status_counters, public.lyrica_status_servers from public, anon, authenticated;
grant select, insert, update, delete on public.lyrica_status_clients, public.lyrica_status_counters, public.lyrica_status_servers to service_role;

create function public.lyrica_status_record(p_id uuid, p_sequence integer, p_network text, p_counts jsonb, p_limit_key text)
returns boolean language plpgsql security invoker set search_path = '' as $$
begin
  perform public.music_presence_limit(p_limit_key, 180);
  insert into public.lyrica_status_clients as c(session_id, sequence, seen_at)
  values (p_id, p_sequence, now())
  on conflict(session_id) do update set sequence = excluded.sequence, seen_at = excluded.seen_at
  where excluded.sequence > c.sequence;
  if not found then return false; end if;

  if p_network is not null then
    insert into public.lyrica_status_servers(day, network, session_id)
    values ((now() at time zone 'UTC')::date, p_network, p_id)
    on conflict do nothing;
  end if;

  insert into public.lyrica_status_counters as c(bucket, metric, total)
  select case when key = 'lrc_requests' then date_trunc('day', now(), 'UTC') else date_trunc('hour', now(), 'UTC') end,
         key, value::bigint
  from jsonb_each_text(p_counts)
  order by key
  on conflict (bucket, metric) do update set total = c.total + excluded.total;
  return true;
end;
$$;

create function public.lyrica_status_snapshot() returns jsonb
language sql stable security invoker set search_path = '' as $$
  with recent as (
    select metric, sum(total)::bigint as count
    from public.lyrica_status_counters
    where bucket >= date_trunc('hour', now(), 'UTC') - interval '23 hours'
      and metric <> 'lrc_requests'
    group by metric
  ), provider_counts as (
    select split_part(metric, '.', 2) as provider,
      sum(count) filter (where split_part(metric, '.', 3) = 'ok') as ok,
      sum(count) filter (where split_part(metric, '.', 3) = 'error') as errors,
      sum(count) filter (where split_part(metric, '.', 3) = 'limited') as limited
    from recent where metric like 'p.%' group by 1
  ), networks as (
    select network, count(distinct session_id) as sessions
    from public.lyrica_status_servers
    where day >= (now() at time zone 'UTC')::date - 6
    group by network having count(distinct session_id) >= 3
    order by sessions desc, network limit 10
  )
  select jsonb_build_object(
    'updated_at', now(),
    'active', (select count(*) from public.lyrica_status_clients where seen_at > now() - interval '3 minutes'),
    'lrc_requests', (select coalesce(sum(total), 0) from public.lyrica_status_counters where metric = 'lrc_requests'),
    'providers', (select coalesce(jsonb_object_agg(provider, jsonb_build_object(
        'ok', coalesce(ok, 0), 'errors', coalesce(errors, 0), 'limited', coalesce(limited, 0))), '{}'::jsonb) from provider_counts),
    'errors', (select coalesce(jsonb_object_agg(split_part(metric, '.', 2), count), '{}'::jsonb)
      from recent where metric like 'e.%'),
    'networks', (select coalesce(jsonb_agg(to_jsonb(networks) order by sessions desc, network), '[]'::jsonb) from networks)
  );
$$;

revoke all on function public.lyrica_status_record(uuid, integer, text, jsonb, text),
  public.lyrica_status_snapshot() from public, anon, authenticated;
grant execute on function public.lyrica_status_record(uuid, integer, text, jsonb, text),
  public.lyrica_status_snapshot() to service_role;

select cron.schedule('lyrica-status-expiry', '12 * * * *', $job$
  delete from public.lyrica_status_clients where seen_at < now() - interval '1 day';
  delete from public.lyrica_status_servers where day < (now() at time zone 'UTC')::date - 6;
  delete from public.lyrica_status_counters where bucket < now() - interval '2 days' and metric <> 'lrc_requests';
$job$);
