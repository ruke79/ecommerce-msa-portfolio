#!/bin/bash
pid=$(pgrep -f cart-service)
if [ -n "${pid}" ]
then
        kill -15 ${pid}
        echo kill process ${pid}
else
        echo no process
fi

chmod +x ./cart-service-0.0.1-SNAPSHOT.jar
nohup java -jar ./cart-service-0.0.1-SNAPSHOT.jar >> application.log 2> /dev/null &
