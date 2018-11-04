#!/bin/sh

set -e # Abort on any error

# ./bin/flink modification --modify pauseSink
# ./bin/flink modification --modify pauseMap
./bin/flink modification --modify resumeMap
# ./bin/flink modification --modify resumeSink
