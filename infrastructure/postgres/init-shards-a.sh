#!/bin/bash
# pg-shard-a hosts logical shards shard_00..shard_07 + the control-plane "directory" database.
set -e
for i in $(seq 0 7); do
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -c "CREATE DATABASE $(printf 'shard_%02d' $i)"
done
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -c "CREATE DATABASE directory"
