-- Metadata only. RLS denies direct client access; Worker holds service role key and
-- authorizes every operation using short-lived DO credentials before writing here.
create extension if not exists pgcrypto;
create table if not exists public.rooms (
  id text primary key,
  status text not null check (status in ('waiting','active','closed')),
  created_at timestamptz not null,
  expires_at timestamptz not null,
  max_participants smallint not null check (max_participants = 2)
);
create table if not exists public.participants (
  id text primary key,
  room_id text not null references public.rooms(id) on delete cascade,
  status text not null check (status in ('active','exited')),
  joined_at timestamptz not null,
  last_activity_at timestamptz not null,
  exited_at timestamptz
);
create table if not exists public.invitations (
  token_hash text primary key,
  room_id text not null references public.rooms(id) on delete cascade,
  expires_at timestamptz not null,
  used boolean not null default false,
  revoked boolean not null default false
);
create table if not exists public.cleanup_tasks (
  id text primary key,
  session_id text not null,
  retry_count integer not null default 0,
  status text not null,
  next_retry_at timestamptz not null,
  completed_at timestamptz
);
alter table public.rooms enable row level security;
alter table public.participants enable row level security;
alter table public.invitations enable row level security;
alter table public.cleanup_tasks enable row level security;
-- No policies: anonymous/authenticated clients have no direct table access.
revoke all on public.rooms, public.participants, public.invitations, public.cleanup_tasks from anon, authenticated;
grant select, insert, update, delete on public.rooms, public.participants, public.invitations, public.cleanup_tasks to service_role;

-- Run every 10 minutes from pg_cron if available on the selected free project.
-- If pg_cron isn't available, Worker scheduled() invokes cleanup instead.
create or replace function public.prune_ephemeral_metadata() returns void
language sql security definer set search_path = public as $$
  delete from cleanup_tasks where status = 'complete' and completed_at < now() - interval '24 hours';
  delete from rooms where expires_at < now();
$$;
revoke all on function public.prune_ephemeral_metadata() from public, anon, authenticated;
grant execute on function public.prune_ephemeral_metadata() to service_role;
