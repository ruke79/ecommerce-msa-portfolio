#!/bin/bash
pid=$(pgrep -f catalog-service)
if [ -n "${pid}" ]
then
        kill -15 ${pid}
        echo kill process ${pid}
else
        echo no process
fi

chmod +x ./catalog-service-0.0.1-SNAPSHOT.jar
nohup java -jar ./catalog-service-0.0.1-SNAPSHOT.jar >> application.log 2> /dev/null &
