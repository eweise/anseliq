# default postgres user and database are created in the entrypoint with initdb.

PG_DB_ROOT ?= 5400
PG_DB_USER ?= docker
PG_DB_PASSWORD ?=mysecretpassword
PG_DB_HOSTNAME := localhost
PG_PASSWORD := postgres
CURRENT_DIR = $(shell pwd)
POSTGRES_BIN= /Library/PostgreSQL/17/bin


#start-postgres:
#	$ docker run --name some-postgres -e POSTGRES_PASSWORD=mysecretpassword -d -p 5432:5432 postgres

#PG_DB_ROOT ?= 5400


create-db: delete-db
	@echo "$(CURRENT_DIR)"
	$(POSTGRES_BIN)/psql -p 5433 -Upostgres -f $(CURRENT_DIR)/backend/db/schema.sql

delete-db:
	$(POSTGRES_BIN)/psql -p 5433 -Upostgres -c "drop database if exists recon_db"
