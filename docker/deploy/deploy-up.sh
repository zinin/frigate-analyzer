#!/bin/sh
cd "$(dirname "$0")"
docker compose pull
docker compose --force-recreate
