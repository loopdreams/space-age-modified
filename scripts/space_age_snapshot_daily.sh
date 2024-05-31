#!/usr/bin/env sh

DB=""
destination=""

timestamp=$(date +"%Y%m%d%H%M%S")

cp "$DB" "$destination$timestamp-games.db"

count=$(find "$destination" -type f | wc -l)

if [ "$count" -lt 15 ]
then
    exit 0
else
    rm -- "$destination/$(ls -rt $destination | head -n 1)"
fi
