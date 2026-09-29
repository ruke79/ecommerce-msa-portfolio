pipeline {
    agent any
    
    parameters {
        string(name: 'SPRING_PROFILE', defaultValue: 'prd', description: '빌드할 Spring Profile을 입력하세요 (e.g., prd, dev, local)')
        string(name: 'CONFIG_SERVER_URL', defaultValue: '', description: 'Spring Cloud Config Server URL') // New parameter
    }

    environment {
        // Jenkins Credentials에 등록된 SSH 키 ID
        SSH_CREDENTIALS = 'ssh-key-id'
        
        // Jenkins Credentials에 등록된 Docker Hub 자격 증명 ID
        DOCKER_HUB_CREDENTIALS = 'docker-hub-credentials-id'
        // Docker Hub 사용자 이름
        DOCKER_HUB_USER = 'username'
        
        // Private Registry URL 및 자격 증명 ID 추가
        PRIVATE_REGISTRY_URL = '10.0.x.x:5000'
        PRIVATE_REGISTRY_CREDENTIALS = 'private-registry-credentials-id'

        // AWS 인스턴스별 호스트 이름 (SSH Config에 정의된 이름)        
        TOKYO_PRIVATE_1 = 'ec2-xxx1.amazonaws.com'
        PUBLIC_INSTANCE = 'ec2-xxx2.amazonaws.com'
        
        // 인스턴스별 다른 키 ID
        TOKYO_SSH_CREDENTIALS = 'tokyo-key-id'
        OREGON_SSH_CREDENTIALS = 'oregon-key-id'
        
        // 서비스가 위치한 디렉터리 경로
        PROJECT_DIR = 'ecommerce-msa'
        
        DOCKER_USER_NAME = 'ecommerce-msa'
        
        // SERVICES_GROUP_1
        DOCKER_SERVICES1_IMAGE = 'docker-compose-part-1.yml'
        // SERVICES_GROUP_2
        DOCKER_SERVICES2_IMAGE = 'docker-compose-part-2.yml'
        
        DOCKER_FILE = 'Dockerfile'
        
        BUILD_NUMBER = '1.0.0'
    }

    stages {
        stage('Checkout') {
            steps {          
                //project_repo, proj_bran_name, config_repo, config_branch_name 수정 필요
                // 1. 설정 파일 저장소 체크아웃
                dir('config') {
                    sh 'rm -rf *'
                    git branch: 'config_branch_name', credentialsId: ${SSH_CREDENTIALS}, url: 'git@github.com:config_repo'
                }
                
                // 2. 애플리케이션 소스 코드 체크아웃
                dir(PROJECT_DIR) {
                    sh 'rm -rf *'
                    git branch: 'proj_branch_name', credentialsId: ${SSH_CREDENTIALS}, url: 'git@github.com:project_repo'
                    
                    sh "cp ../config/config/.env ."
                    sh "cp ../config/config/docker-compose-part-1.yml ."
                    sh "cp ../config/config/docker-compose-part-2.yml ."
                }
            }
        }
        
        stage('Build & Inject Config') {
            steps {
                script {
                    def services = ['eureka-server','gateway-server','user-service', 'catalog-service', 'cart-service', 'order-service', 'pay-service', 'coupon-service', 'chat-service']
                    def springProfile = params.SPRING_PROFILE
                    
                    
                    for (service in services) {
                        echo "Building ${service} with its configuration..."
                        
                        dir("${PROJECT_DIR}/${service}") {
                            sh "cp ../../config/config/${service}/application-${springProfile}.yml src/main/resources/application-${springProfile}.yml"
                            sh "cp ../../config/config/${service}/bootstrap-${springProfile}.yml src/main/resources/bootstrap-${springProfile}.yml"
                        }
                    }
                    
                    dir("${PROJECT_DIR}/common") {
                        sh "cp ../../config/config/common/application-common-${springProfile}.yml src/main/resources/application-common-${springProfile}.yml"
                    }
                    
                    dir(PROJECT_DIR) {
                        sh "if [ ! -f ./gradlew ]; then gradle wrapper; fi"
                        sh "chmod +x ./gradlew"
                        
                        echo "Building all services with Gradle..."
                        //sh "./gradlew bootJar -Pargs='--spring.profiles.active=prd'"
                        sh "./gradlew bootJar -Pargs='--spring.profiles.active=${springProfile}'"
                    }
                }
            }
        }
        
        stage('Build and Push Images to Private Registry') {
            steps {
                script {
                    dir(PROJECT_DIR) {
                        def all_services = ['eureka-server', 'gateway-server', 'catalog-service', 'user-service', 'coupon-service', 'chat-service', 'cart-service', 'order-service', 'pay-service']
                        def springProfile = params.SPRING_PROFILE
                        def configServerUrl = params.CONFIG_SERVER_URL
                        
                        docker.withRegistry("https://${PRIVATE_REGISTRY_URL}", PRIVATE_REGISTRY_CREDENTIALS) {
                            all_services.each { service ->
                                echo "Building and pushing ${service} to ${PRIVATE_REGISTRY_URL}"
                                
                                // 로컬에서 기존 이미지 삭제
                                sh "docker image rm -f ${PRIVATE_REGISTRY_URL}/${DOCKER_USER_NAME}/${service}:${env.BUILD_NUMBER}"
                                
                                // 원격 레지스트리에서 'latest' 태그 삭제
                                withCredentials([usernamePassword(credentialsId: "${PRIVATE_REGISTRY_CREDENTIALS}", usernameVariable: 'PRIVATE_REGISTRY_USER', passwordVariable: 'PRIVATE_REGISTRY_PASSWORD')]) {
                                    sh '''
                                        DIGEST=$(curl -s -X GET -H "Accept: application/vnd.docker.distribution.manifest.v2+json" -u "${PRIVATE_REGISTRY_USER}:${PRIVATE_REGISTRY_PASSWORD}" https://${PRIVATE_REGISTRY_URL}/v2/ecommerce-msa/''' + service + '''/manifests/latest | jq -r '.config.digest')
                                        
                                        if [ -n "$DIGEST" ] && [ "$DIGEST" != "null" ]; then
                                            curl -s -X DELETE -u "${PRIVATE_REGISTRY_USER}:${PRIVATE_REGISTRY_PASSWORD}" https://${PRIVATE_REGISTRY_URL}/v2/ecommerce-msa/''' + service + '''/manifests/$DIGEST
                                            echo "Deleted remote 'latest' tag for ''' + service + '''"
                                        else
                                            echo "No 'latest' tag found or failed to get digest for ''' + service + '''"
                                        fi
                                    '''
                                }
                                
                                // Dockerfile
                                //def image = docker.build("${PRIVATE_REGISTRY_URL}/${DOCKER_USER_NAME}/${service}:${env.BUILD_NUMBER}", "-f ./${service}/${DOCKER_FILE} ./${service}")
                                                                
                                def image = docker.build(
                                    "${PRIVATE_REGISTRY_URL}/${DOCKER_USER_NAME}/${service}:${env.BUILD_NUMBER}",
                                    """-f ./${service}/${DOCKER_FILE} \\
                                    --build-arg SERVICE_DIR=${service} \\
                                    --build-arg SPRING_PROFILES_ACTIVE=${springProfile} \\
                                    --build-arg CONFIG_SERVER_URL=${configServerUrl} \\
                                    --cache-from=${PRIVATE_REGISTRY_URL}/${DOCKER_USER_NAME}/${service}:latest ."""
                                )
                                // 이미지를 프라이빗 레지스트리로 푸시
                                image.push()
                                
                                // 'latest' 태그도 함께 푸시
                                image.push('latest')
                            }
                        }
                    }
                }
            }
        }
        
        // stage('Cleanup') {
        //     steps {
        //         echo "Clearing unused Docker images and build cache..."
        //         sh 'docker system prune -a -f --volumes'
        //     }
        // }
        
        stage('Deploy to Instances') {
            steps {
                script {
                    dir(PROJECT_DIR) {
                        withCredentials([usernamePassword(credentialsId: "${PRIVATE_REGISTRY_CREDENTIALS}", usernameVariable: 'PRIVATE_REGISTRY_USER', passwordVariable: 'PRIVATE_REGISTRY_PASSWORD')]) {
                            
                            // --- 1. 서비스 그룹 #1을 도쿄 인스턴스(퍼블릭)에 배포 ---
                            sshagent([TOKYO_SSH_CREDENTIALS]) {
                                echo "Deploying Services Group #1 to Tokyo Instance #1: ${PUBLIC_INSTANCE}"
                                sh "scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null .env ${DOCKER_SERVICES1_IMAGE} ubuntu@${PUBLIC_INSTANCE}:/home/ubuntu/apps/"
                                sh "ssh ubuntu@${PUBLIC_INSTANCE} 'sudo usermod -aG docker ubuntu && echo ${PRIVATE_REGISTRY_PASSWORD} | docker login -u ${PRIVATE_REGISTRY_USER} --password-stdin ${PRIVATE_REGISTRY_URL} && cd /home/ubuntu/apps && docker compose -f ${DOCKER_SERVICES1_IMAGE} pull && docker compose -f ${DOCKER_SERVICES1_IMAGE} up -d --force-recreate'"
                            }
                            
                            // --- 2. 서비스 그룹 #2를 도쿄 인스턴스(프라이빗)에 배포 ---
                            sshagent([TOKYO_SSH_CREDENTIALS]) {
                                echo "Deploying Services Group #2 to Tokyo Private Instance #2: ${TOKYO_PRIVATE_1}"
                                sh "scp -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null .env ${DOCKER_SERVICES2_IMAGE} ubuntu@${TOKYO_PRIVATE_1}:/home/ubuntu/apps/"
                                sh "ssh ubuntu@${TOKYO_PRIVATE_1} 'sudo usermod -aG docker ubuntu && echo ${PRIVATE_REGISTRY_PASSWORD} | docker login -u ${PRIVATE_REGISTRY_USER} --password-stdin ${PRIVATE_REGISTRY_URL} && cd /home/ubuntu/apps && docker compose -f ${DOCKER_SERVICES2_IMAGE} pull && docker compose -f ${DOCKER_SERVICES2_IMAGE} up -d --force-recreate'"
                            }
                        }
                    }
                }
            }
        }
    }
}