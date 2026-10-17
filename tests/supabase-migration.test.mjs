// Optional SQL verification on embedded PostgreSQL (no Supabase credentials).
// npm install --no-save --package-lock=false @electric-sql/pglite
// node --test tests/supabase-migration.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { PGlite } = require(process.env.ECHO_PGLITE_MODULE || '@electric-sql/pglite');
const migration = await readFile(new URL('../supabase/migrations/20261017_echo_history.sql', import.meta.url), 'utf8');

test('Supabase SQL applies twice, protects Data API roles and cascades account/thread deletion', async t => {
  const db = new PGlite(); t.after(() => db.close());
  await db.exec('create role anon; create role authenticated; create table public.users(user_id uuid primary key);');
  await db.exec(migration); await db.exec(migration);
  const flags = await db.query("select relname, relrowsecurity from pg_class where relname in ('echo_threads','echo_messages') order by relname");
  assert.equal(flags.rows.length, 2); assert.ok(flags.rows.every(r => r.relrowsecurity));
  for (const role of ['anon', 'authenticated']) {
    await db.exec(`set role ${role}`);
    await assert.rejects(db.query('select * from public.echo_threads'), e => e.code === '42501');
    await assert.rejects(db.query('select * from public.echo_messages'), e => e.code === '42501');
    await db.exec('reset role');
  }
  const owner = '11111111-1111-1111-1111-111111111111';
  const thread = '22222222-2222-2222-2222-222222222222';
  const message = '33333333-3333-3333-3333-333333333333';
  const request = '44444444-4444-4444-4444-444444444444';
  await db.query('insert into users values ($1)', [owner]);
  await db.query('insert into echo_threads(thread_id,owner_id,title,created_at,updated_at) values ($1,$2,$3,now(),now())', [thread, owner, 'Saved conversation']);
  await db.query('insert into echo_messages values ($1,$2,$3,$4,$5,$6,now())', [message, thread, request, 'user', 'Hello', 1]);
  assert.equal((await db.query('select content from echo_messages')).rows[0].content, 'Hello');
  await assert.rejects(db.query('insert into echo_messages values ($1,$2,$3,$4,$5,$6,now())',
    ['55555555-5555-5555-5555-555555555555', thread, request, 'user', 'Duplicate', 2]), e => e.code === '23505');
  await db.query('delete from users where user_id=$1', [owner]);
  assert.equal((await db.query('select count(*)::int as n from echo_threads')).rows[0].n, 0);
  assert.equal((await db.query('select count(*)::int as n from echo_messages')).rows[0].n, 0);
});
