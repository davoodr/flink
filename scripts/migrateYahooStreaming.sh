#!/bin/sh

set -e # Abort on any error

./bin/flink modification --modify jobmanagerIDs

echo "Input: Map executionAttemptID to migrate"
read instance

echo "Input: TaskmanagerID, where map instance should be resumed."
read taskManagerIDtoRestart

./bin/flink modification --modify pauseSingleOperatorInstance:$instance
read ignored
./bin/flink modification --modify restartOperatorInstance:$taskManagerIDtoRestart
