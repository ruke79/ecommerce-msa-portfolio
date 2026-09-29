#! /bin/bash

chmod +x ./discovery-service/deploy.sh
chmod +x ./gateway-service/deploy.sh
chmod +x ./user-server/deploy.sh
chmod +x ./catalog-server/deploy.sh
chmod +x ./chat-service/deploy.sh


./discovery-service/deploy.sh
./gateway-service/deploy.sh
./user-server/deploy.sh
./catalog-server/deploy.sh
./chat-service/deploy.sh
