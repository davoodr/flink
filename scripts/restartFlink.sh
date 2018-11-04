#!/bin/bash

set +x

function stop_flink {
  rm log/*
  rm -rf sinkTextOutput*
  rm -rf jobOutput*
  rm -rf operatorStateJobSink*
  rm -rf benchmarkOutput*
  ./bin/flink-daemon.sh stop-all taskmanager
  ./bin/flink-daemon.sh stop-all jobmanager
  ./bin/stop-local.sh
}

function restart_flink {
  stop_flink
  start_flink $1
}

function start_flink() {

  # Asign fixed port for debugging job manager
  sed -i -E "s/(env.java.opts: -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=)(.+)/\15005/" conf/flink-conf.yaml
  ./bin/start-local.sh

  sleep 1 # Wait so that job manger could start

  if [ $# -ne 0 ] # #Parameters not equals to 0
  then
    echo "Starting $1 Taskmanager"
    for i in `seq 1 $1`;  
    do
      # Assign different ports in flink-conf.yaml, so that we can debug each tm/jm indepedently. Otherwise starting will fail with port-in-use exception.
      port=$(($i + 5005))
      sed -i -E "s/(env.java.opts: -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=)(.+)/\1$port/" conf/flink-conf.yaml
      ./bin/flink-daemon.sh start taskmanager --configDir conf
      sleep 1 # Wait so that task manger could start
    done
  else
    echo "Only starting Jobmanager"
  fi
}

case "$1" in
  "stop" | "s" )
    echo "Stopping Flink"
    stop_flink
    ;;

  "restart" | "r" )
    echo "Restart Flink"
    restart_flink
    ;;

  * )
    # Default option.	  
    echo "Default: Restart Flink"
    restart_flink $1
    ;;

esac

exit 0
