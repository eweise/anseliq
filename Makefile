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


create-db: delete-db
	@echo "$(CURRENT_DIR)"
	$(POSTGRES_BIN)/psql -p 5432 -Upostgres -f $(CURRENT_DIR)/backend/db/schema.sql

delete-db:
	$(POSTGRES_BIN)/psql -p 5432 -Upostgres -c "drop database if exists recon_db"

# Adds a "Happy Path <id>" fund flow with ten days of history ending today (see
# HappyPathScenario). Stop the backend first: its workers would race the seeder for its jobs.
seed-demo:
	sbt "backend/testOnly anseliq.testing.SeedHappyPathDemo"

# Same, with the match suggestions from a local model served by Ollama (see
# match-suggestions.local-llm in application.conf): `ollama serve`, `ollama pull qwen2.5:7b`.
seed-demo-llm:
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
