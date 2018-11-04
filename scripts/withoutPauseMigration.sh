#!/bin/sh

set -e # Abort on any error

./bin/flink modification --modify jobmanagerIDs

echo "Input: Map executionAttemptID to migrate"
read mapInstance

echo "Input: TaskmanagerID, where map instance should be resumed."
read taskManagerIDtoRestart

./bin/flink modification --modify pauseSingleOperatorInstance:$mapInstance
read ignored
./bin/flink modification --modify restartOperatorInstance:$taskManagerIDtoRestart
read ignored
./bin/flink modification --modify modifySink
