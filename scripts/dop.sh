#!/bin/sh

set -x

./bin/flink modification --modify pauseSink
./bin/flink modification --modify pauseFilter
./bin/flink modification --modify pauseMap
./bin/flink modification --modify increaseDoPForMap
./bin/flink modification --modify resumeMap
./bin/flink modification --modify increaseDoPForFilter
./bin/flink modification --modify resumeFilter
./bin/flink modification --modify increaseDoPForSink
./bin/flink modification --modify resumeSink
