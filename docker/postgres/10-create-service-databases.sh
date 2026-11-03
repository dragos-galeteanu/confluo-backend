#!/bin/sh
# Creates the databases belonging to services other than the one POSTGRES_DB names.
#
# The official PostgreSQL image creates exactly one database, from POSTGRES_DB, and the platform
# service has it. Every other service needs its own, because common-amqp ships one `outbox` and one
# `consumed_message` table per service and two services sharing a database would collide on both.
#
# Run by the image's entrypoint, which executes everything in /docker-entrypoint-initdb.d in
# filename order. A .sh file rather than a .sql one because the database names come from the
# environment, and plain SQL cannot read it.
#
# WORTH KNOWING, because this is how it bites: the entrypoint runs these scripts ONLY when it
# initialises an empty data directory. On a volume that already holds a cluster - anyone who ran
# this compose file before this script existed - it is skipped silently, the service starts, and
# Flyway fails on a database that is not there. The fix is either
#
#   docker compose down -v          # discards all local data, then up again
#
# or creating it by hand, once:
#
#   docker compose exec postgres createdb -U confluo confluo_notifications

set -eu

for database in ${SERVICE_DATABASES:-}; do
    # Idempotent on purpose. The entrypoint will not run this twice, but a person pasting it into
    # a running container should not have to care whether they already did.
    if psql -tAqc "SELECT 1 FROM pg_database WHERE datname = '${database}'" \
            --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" | grep -q 1; then
        echo "database ${database} already exists, leaving it alone"
    else
        echo "creating database ${database}"
        # No OWNER clause: the connection is POSTGRES_USER's, so the database is already theirs.
        createdb --username "$POSTGRES_USER" "${database}"
    fi
done
