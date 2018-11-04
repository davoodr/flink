#!/bin/sh

set -e # Abort on any error

./bin/flink modification --modify jobmanagerIDs

echo "Input: taskmanagerID, where all operator instances should be migrated"
read taskManagerIDtoStop

./bin/flink modification --modify migrateAll:$taskManagerIDtoStop
