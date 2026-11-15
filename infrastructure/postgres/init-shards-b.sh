#!/bin/bash
# pg-shard-b hosts logical shards shard_08..shard_15.
set -e
for i in $(seq 8 15); do
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -c "CREATE DATABASE $(printf 'shard_%02d' $i)"
done
