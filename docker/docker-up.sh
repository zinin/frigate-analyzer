#!/bin/sh

docker compose pull
#docker compose -f docker-compose.yml build --no-cache
#docker compose -f docker-compose.yml up
docker compose -f docker-compose.yml up --build
#docker compose -f docker-compose.yml up --build --force-recreate
