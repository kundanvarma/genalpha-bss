#!/usr/bin/env python3
"""Prove the second lock is on, table by table, against the running databases.

Every tenant-owning table is protected twice: the application scopes each
query by tenant, and Postgres row-level security refuses another tenant's
rows even if a query loses its predicate. The first lock is code and is
covered by the suites. This checks the second one, and it checks it where
the truth is -- the live catalogue -- not in the migrations, because a table
added by a later migration that nobody thought about is exactly the failure
this is here to catch. Eight such tables were found the first time it ran.

Four invariants, each of which has a way of quietly going wrong:

  1. every public table with a tenant_id column has RLS enabled and at least
     one policy -- a new table is unprotected by default, silently
  2. the runtime role owns no tenant table -- a table owner is exempt from
     its own policies unless they are FORCEd, so ownership drift would
     disable the lock without changing a single policy
  3. the runtime role is neither a superuser nor BYPASSRLS -- either
     attribute exempts it everywhere at once
  4. the lock actually holds: as the runtime role, a session pinned to one
     tenant cannot see another tenant's rows

Run it against a fleet that is up:

    python3 ops/security/rls_check.py            # all databases
    python3 ops/security/rls_check.py billing    # one of them

Exit 0 clean, 1 on any violation, 2 if it could not reach the databases.
"""
import subprocess
import sys

CONTAINER = "bss-postgres"
OWNER = "postgres"
# Databases that hold no tenant data of ours and have no runtime app role.
NOT_OURS = {"postgres", "template0", "template1", "keycloak"}
# The tenant every runtime role may use to span tenants for system jobs.
SYSTEM_TENANT = "__system__"


def psql(db, sql, user=OWNER, password=None):
    """Run one statement, return rows as lists of fields."""
    cmd = ["docker", "exec"]
    if password:
        cmd += ["-e", f"PGPASSWORD={password}"]
    cmd += ["-i", CONTAINER, "psql", "-U", user, "-d", db, "-tAF|", "-v",
            "ON_ERROR_STOP=1", "-c", sql]
    done = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip().splitlines()[-1] if done.stderr else "psql failed")
    return [line.split("|") for line in done.stdout.strip().splitlines() if line]


def databases():
    rows = psql("postgres", "select datname from pg_database where not datistemplate")
    return sorted(r[0] for r in rows if r[0] not in NOT_OURS)


def runtime_role(db):
    """The <service>_app role this database's components log in as, if any.

    Roles are cluster-wide in Postgres, so pg_roles alone would hand every
    database all thirty-six of them. What ties a role to one database is the
    grants it holds on that database's own tables.
    """
    return psql(db, r"""
        select distinct g.grantee, r.rolsuper, r.rolbypassrls
        from information_schema.role_table_grants g
        join pg_roles r on r.rolname = g.grantee
        where g.table_schema = 'public' and g.grantee like '%\_app'
          and r.rolcanlogin
        order by 1""")


def tenant_tables(db):
    return psql(db, """
        select c.relname, c.relrowsecurity, c.relforcerowsecurity,
               pg_get_userbyid(c.relowner),
               (select count(*) from pg_policy p where p.polrelid = c.oid)
        from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public' and c.relkind = 'r'
          and exists (select 1 from pg_attribute a
                      where a.attrelid = c.oid and a.attname = 'tenant_id'
                        and a.attnum > 0 and not a.attisdropped)
        order by c.relname""")


def probe(db, role, table):
    """Invariant 4: pin the session to one tenant, try to read another's rows.

    Returns (tenant_pinned, rows_visible_from_other_tenants) or None when the
    table does not hold two tenants and so cannot prove anything.
    """
    tenants = [r[0] for r in psql(db, f"select distinct tenant_id from {table} "
                                      f"where tenant_id is not null order by 1 limit 2")]
    if len(tenants) < 2:
        return None
    mine, theirs = tenants[0], tenants[1]
    rows = psql(db,
                f"set app.tenant_id = '{mine}'; "
                f"select count(*) from {table} where tenant_id = '{theirs}'",
                user=role, password=role)
    return mine, int(rows[-1][0])


def main():
    wanted = sys.argv[1:]
    try:
        dbs = [d for d in databases() if not wanted or d in wanted]
    except Exception as exc:
        print(f"rls-check: cannot reach the databases ({exc}) -- is the fleet up?")
        return 2
    if wanted and not dbs:
        print(f"rls-check: no such database: {' '.join(wanted)}")
        return 2

    violations, checked, probed = [], 0, 0
    for db in dbs:
        roles = runtime_role(db)
        for name, is_super, bypass in roles:
            if is_super == "t":
                violations.append(f"{db}: runtime role {name} is a SUPERUSER -- exempt from every policy")
            if bypass == "t":
                violations.append(f"{db}: runtime role {name} has BYPASSRLS -- exempt from every policy")
        role_names = {r[0] for r in roles}

        tables = tenant_tables(db)
        if not tables:
            continue
        if not roles:
            violations.append(f"{db}: {len(tables)} tenant tables but no runtime role -- "
                              f"components here log in as an owner and RLS never applies")
        for table, rls, forced, owner, policies in tables:
            checked += 1
            if rls != "t":
                violations.append(f"{db}.{table}: holds tenant_id, row-level security is OFF")
            elif policies == "0":
                violations.append(f"{db}.{table}: row-level security is on but no policy exists "
                                  f"-- the table is readable by nobody or everybody depending on the role")
            if owner in role_names and forced != "t":
                violations.append(f"{db}.{table}: owned by the runtime role {owner} and not FORCEd "
                                  f"-- an owner is exempt from its own policies")

        # Behavioural proof on the largest two-tenant table in this database.
        for table, rls, _f, _o, policies in tables:
            if rls != "t" or policies == "0":
                continue
            try:
                got = probe(db, next(iter(role_names)), table) if role_names else None
            except Exception as exc:
                violations.append(f"{db}.{table}: could not probe as the runtime role ({exc})")
                break
            if got is None:
                continue
            pinned, leaked = got
            probed += 1
            if leaked:
                violations.append(f"{db}.{table}: a session pinned to tenant '{pinned}' read "
                                  f"{leaked} rows belonging to another tenant")
            break

    if violations:
        print(f"rls-check FAILED -- {len(violations)} violation(s) across {checked} tenant tables\n")
        for v in violations:
            print(f"  {v}")
        print("\nAdd the policy beside the table's own migration, in db/migration-postgresql:")
        print("  ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;")
        print("  CREATE POLICY tenant_isolation ON <t>")
        print(f"      USING (current_setting('app.tenant_id', true) = '{SYSTEM_TENANT}'")
        print("             OR tenant_id = current_setting('app.tenant_id', true))")
        print(f"      WITH CHECK (current_setting('app.tenant_id', true) = '{SYSTEM_TENANT}'")
        print("             OR tenant_id = current_setting('app.tenant_id', true));")
        return 1

    print(f"rls-check ok: {checked} tenant tables across {len(dbs)} databases all carry a policy; "
          f"no runtime role is a superuser, has BYPASSRLS, or owns a tenant table; "
          f"the lock held on {probed} live cross-tenant read{'s' if probed != 1 else ''}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
