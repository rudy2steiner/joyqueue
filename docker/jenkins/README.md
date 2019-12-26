
# Jenkins docker build and start


## Docker build 

docker build -t jenkins/jenkins:lts_python35 .


## Docker run 


### bridge network mode

docker run  -p 80:8080  -v /export/docker/jenkins/jenkins_home:/var/jenkins_home -v /var/run/docker.sock:/var/run/docker.sock -v $(which docker):/usr/bin/docker -u0 -d jenkins/jenkins:lts_python35


### host network mode

docker run  --network host -e JENKINS_OPTS="--httpPort=80" -v /export/docker/jenkins/jenkins_home:/var/jenkins_home -v /var/run/docker.sock:/var/run/docker.sock -v $(which docker):/usr/bin/docker -u0 -d jenkins/jenkins:lts_python35


## 在Jenkins管理端新建CI/CD项目
 1. 在Jenkins 新建github项目： http://10.194.144.xx/project/joyqueue    
    - new 任务(MyJoyqueue)、选择Build Triggers（Push Events、Opened Merge Request Events）
    - 生成secret token（后续在Gitlab项目中配置需要）: build when change -> advance-> generate->secret token(jenkins configure)
    - 在Gitlab Setting -> Integrations 为 MyJoyqueue配置Webhook:选择事件Push events、Merge request events
    - 测试 Webhook 
    
    