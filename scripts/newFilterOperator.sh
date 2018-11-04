#!/bin/sh

set -e # Abort on any error

./bin/flink modification --modify pauseAll:map
read ignored
./bin/flink modification --modify startFilter:3
read ignored
./bin/flink modification --modify modifyMapForFilter
read ignored
./bin/flink modification --modify resumeMap
