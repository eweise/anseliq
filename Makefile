# default postgres user and database are created in the entrypoint with initdb.

PG_DB_ROOT ?= 5432
PG_DB_USER ?= postgres
PG_DB_PASSWORD ?=postgres
PG_DB_HOSTNAME := localhost
PG_PASSWORD := postgres
CURRENT_DIR = $(shell pwd)
POSTGRES_BIN= /Library/PostgreSQL/18/bin
DB_NAME := recon_db
DB_SNAPSHOT_FILE := $(CURRENT_DIR)/backend/db/snapshot.dump


#start-postgres:
#	$ docker run --name some-postgres -e POSTGRES_PASSWORD=mysecretpassword -d -p 5432:5432 postgres

#PG_DB_ROOT ?= 5400

start-localstack:
	localstack start -d

stop-localstack:
	localstack stop


# A new, empty recon_db with the schema from the backend's Flyway migrations
# (backend/src/main/resources/db/migration).
create-db: delete-db
	$(POSTGRES_BIN)/createdb -p 5432 -Upostgres $(DB_NAME)
	sbt "backend/runMain anseliq.app.Main migrate"

delete-db:
	$(POSTGRES_BIN)/psql -p 5432 -Upostgres -c "drop database if exists recon_db"

# Adds a "Happy Path <id>" ledger with ten days of history ending today (see
# HappyPathScenario). Stop the backend first: its workers would race the seeder for its jobs.
# Both seed targets restart sbt's background server first: the seeder runs in it, and it keeps the
# environment it started with, so DEMO_SUGGESTER would otherwise be whatever it was back then.
seed-demo:
	sbt --client shutdown
	sbt "backend/testOnly anseliq.testing.SeedHappyPathDemo"

# Same, with the match suggestions from a local model served by Ollama (see
# match-suggestions.local-llm in application.conf): `ollama serve`, `ollama pull qwen2.5:7b`.
seed-demo-llm:
	sbt --client shutdown
	DEMO_SUGGESTER=local-llm sbt "backend/testOnly anseliq.testing.SeedHappyPathDemo"

# A fresh database with the demo data in it.
reset-demo: create-db seed-demo

snapshot-db:
	$(POSTGRES_BIN)/pg_dump -p $(PG_DB_ROOT) -U$(PG_DB_USER) -Fc -f $(DB_SNAPSHOT_FILE) $(DB_NAME)
	@echo "Snapshot written to $(DB_SNAPSHOT_FILE)"

restore-db: delete-db
	$(POSTGRES_BIN)/createdb -p $(PG_DB_ROOT) -U$(PG_DB_USER) $(DB_NAME)
	$(POSTGRES_BIN)/pg_restore -p $(PG_DB_ROOT) -U$(PG_DB_USER) -d $(DB_NAME) $(DB_SNAPSHOT_FILE)

kill-java:
	-pkill -x java
