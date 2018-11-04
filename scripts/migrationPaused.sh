#!/bin/sh

set -e # Abort on any error

./bin/flink modification --modify jobmanagerIDs

echo "Input: taskmanagerID, where the map operation should be stoped."
read taskManagerIDtoStop

echo "Input: taskmanagerID, where map operation should be resumed."
read taskManagerIDtoRestart

./bin/flink modification --modify pauseSink
read d
./bin/flink modification --modify pauseMap
read d
./bin/flink modification --modify stopMapInstance:$taskManagerIDtoStop
read d
./bin/flink modification --modify restartMapInstance:$taskManagerIDtoRestart
read d
./bin/flink modification --modify resumeMap
read d
./bin/flink modification --modify modifySinkInstance
read d
./bin/flink modification --modify resumeSink
read d
