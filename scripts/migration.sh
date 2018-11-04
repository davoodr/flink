#!/bin/sh

set -e # Abort on any error

./bin/flink modification --modify jobmanagerIDs

echo "Input: taskmanagerID, where the map operation should be stoped."
read taskManagerIDtoStop

echo "Input: taskmanagerID, where map operation should be resumed."
read taskManagerIDtoRestart

./bin/flink modification --modify pauseSink
./bin/flink modification --modify pauseMap
./bin/flink modification --modify stopMapInstance:$taskManagerIDtoStop
./bin/flink modification --modify restartMapInstance:$taskManagerIDtoRestart
./bin/flink modification --modify resumeMap
./bin/flink modification --modify modifySinkInstance
./bin/flink modification --modify resumeSink
