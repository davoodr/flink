#!/bin/sh

set -e # Abort on any error
# set -x # Echo commands as they are executed

savepoint_directory='savepoints'

echo "Input: JobID to take savepoint and to resume"
read jobID

start=`date +%s` # Show starting time, with nanos +%s:%N
echo "Starting time: $start"

echo "Canceling job for ID $jobID"
./bin/flink cancel -s $savepoint_directory $jobID 

savepoint="`find $savepoint_directory -mindepth 1 -maxdepth 1 -type d`"

if [ -n "$savepoint" ];
then
	echo "Found savepoint: $savepoint";
else
	echo "Could not finde appropriate savepoint";
	exit
fi

# Change DoP slightly
echo "Resuming job"
./bin/flink run -s $savepoint -c de.adrianbartnik.job.stateful.IndependentOperatorStateJob ../../masterthesis-jobs-1.0-SNAPSHOT.jar -maxNumberOfMessages 1600000 --sourceParallelism 70 --mapParallelism 80 --sinkParallelism 50 --pauseDuration 3 &

echo "Waiting for complete resuming..."
read ignored

end=`date +%s` # Show starting time, with nanos +%s:%N
echo "Savepoint&Resume took "$(($end - $start))""
