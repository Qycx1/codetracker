-- Run once in the SQL Editor of the SAME Supabase project used by DB_URL.
-- Uses CodeTracker public.users.user_id, NOT Supabase auth.users IDs.
begin;
create table if not exists public.echo_threads (
  thread_id uuid primary key,
  owner_id uuid not null references public.users(user_id) on delete cascade,
  classroom_id uuid,
  title varchar(80) not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  next_position bigint not null default 1,
  pending_request_id uuid,
  pending_until timestamptz
);
create index if not exists echo_threads_owner_scope_updated
  on public.echo_threads(owner_id, classroom_id, updated_at desc, thread_id desc);
create table if not exists public.echo_messages (
  message_id uuid primary key,
  thread_id uuid not null references public.echo_threads(thread_id) on delete cascade,
  request_id uuid not null,
  role varchar(9) not null check (role in ('user', 'assistant')),
  content text not null,
  position bigint not null,
  created_at timestamptz not null,
  unique(thread_id, position),
  unique(thread_id, request_id, role)
);
-- Deny direct browser/Data API access. The trusted Spring JDBC connection owns
-- these tables (or uses a server-only BYPASSRLS role); Java enforces user ownership.
alter table public.echo_threads enable row level security;
alter table public.echo_messages enable row level security;
revoke all on public.echo_threads, public.echo_messages from public, anon, authenticated;
commit;
