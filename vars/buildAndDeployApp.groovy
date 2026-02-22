def call(Map config) {
    if (!config.repoUrl) error "repoUrl 파라미터는 필수입니다."
    if (!config.repoBranch) error "repoBranch 파라미터는 필수입니다."

    config.dockerRegistry = config.dockerRegistry ?: 'harbor.thisisserver.com/library'
    config.k8sConfigsRepoUrl = config.k8sConfigsRepoUrl ?: 'git@github.com:WindowsHyun/kubernetes-configs.git'
    config.k8sConfigsBranch = config.k8sConfigsBranch ?: 'develop'
    config.k8sKustomizePathPrefix = config.k8sKustomizePathPrefix ?: 'apps/dev'
    config.credentialId = config.credentialId ?: 'jenkins-ssh-credential'
    config.jenkinsUserEmail = config.jenkinsUserEmail ?: 'jenkins@thisisserver.com'
    config.jenkinsUserName = config.jenkinsUserName ?: 'Jenkins'
    config.kubernetesAgentLabel = config.kubernetesAgentLabel ?: 'builder-k3s'
    config.kubernetesServiceAccount = config.kubernetesServiceAccount ?: 'jenkins-admin'
    config.kubernetesNamespace = config.kubernetesNamespace ?: 'devops'
    config.kubernetesCloud = config.kubernetesCloud ?: 'k3s'
    config.harborCredentialId = config.harborCredentialId ?: 'harbor'
    config.deployToK8s = config.get('deployToK8s', true)

    def services = config.services
    if (!services) {
        if (!config.appName) error "appName 파라미터는 필수입니다."
        if (!config.buildType) error "buildType 파라미터는 필수입니다."

        services = [[
            name: config.appName,
            buildType: config.buildType,
            dockerfilePath: config.dockerfilePath ?: 'Dockerfile',
            buildContext: config.buildContext ?: '.',
            buildWorkdir: config.buildWorkdir ?: '.',
            deployToK8s: config.get('deployToK8s', true),
            k8sAppName: (config.appName ?: '').toLowerCase()
        ]]
    }

    services.each { svc ->
        if (!svc.name) error "services[].name 파라미터는 필수입니다."
        if (!svc.buildType) error "services[${svc.name}].buildType 파라미터는 필수입니다."

        svc.dockerfilePath = svc.dockerfilePath ?: 'Dockerfile'
        svc.buildContext = svc.buildContext ?: '.'
        svc.buildWorkdir = svc.buildWorkdir ?: '.'
        svc.deployToK8s = svc.containsKey('deployToK8s') ? svc.deployToK8s : config.deployToK8s
        svc.k8sAppName = svc.k8sAppName ?: svc.name.toLowerCase()
        svc.imageRepo = "${config.dockerRegistry}/${svc.name.toLowerCase()}"
    }

    pipeline {
        agent {
            kubernetes {
                cloud config.kubernetesCloud
                inheritFrom config.kubernetesAgentLabel
                serviceAccount config.kubernetesServiceAccount
                namespace config.kubernetesNamespace
            }
        }

        stages {
            stage('Checkout Code') {
                steps {
                    echo "Git 저장소 코드 체크아웃: ${config.repoUrl} (${config.repoBranch})"
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: "*/${config.repoBranch}"]],
                        userRemoteConfigs: [[url: config.repoUrl, credentialsId: config.credentialId]],
                        extensions: [
                            [$class: 'CleanBeforeCheckout'],
                            [$class: 'LocalBranch', localBranch: config.repoBranch]
                        ]
                    ])
                }
            }

            stage('Get Git Commit Info') {
                steps {
                    script {
                        env.GIT_COMMIT_SHORT_HASH = sh(returnStdout: true, script: 'git rev-parse --short=7 HEAD').trim()
                        env.GIT_COMMIT_FULL_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        env.GIT_COMMIT_MESSAGE_RAW = sh(returnStdout: true, script: 'git log -1 --pretty=%s').trim()
                        def dateTime = sh(returnStdout: true, script: 'date +%Y%m%d').trim()
                        env.DOCKER_IMAGE_TAG = "${dateTime}_${env.BUILD_NUMBER}"
                        echo "Docker Image Tag: ${env.DOCKER_IMAGE_TAG}"
                    }
                }
            }

            stage('Build and Push Services') {
                steps {
                    script {
                        services.each { svc ->
                            stage("Build ${svc.name}") {
                                container('jnlp') {
                                    dir(svc.buildWorkdir) {
                                        if (svc.buildType == 'go') {
                                            withEnv([
                                                "GOMODCACHE=${env.WORKSPACE}/.cache/go/pkg/mod",
                                                "GOCACHE=${env.WORKSPACE}/.cache/go/build",
                                                "GOPATH=${env.WORKSPACE}/.cache/go"
                                            ]) {
                                                sh 'mkdir -p "$GOMODCACHE" "$GOCACHE" "$GOPATH"'
                                                sh 'go version'
                                                sh 'go env GOMODCACHE GOCACHE GOPATH'
                                                sh 'go mod download'
                                                sh 'go build -v ./...'
                                            }
                                        } else if (svc.buildType == 'npm') {
                                            withEnv(["NPM_CONFIG_CACHE=${env.WORKSPACE}/.cache/npm"]) {
                                                sh 'mkdir -p "$NPM_CONFIG_CACHE"'
                                                sh 'npm install'
                                                sh 'npm run build'
                                            }
                                        } else if (svc.buildType == 'nextjs') {
                                            withEnv(["NPM_CONFIG_CACHE=${env.WORKSPACE}/.cache/npm"]) {
                                                sh 'mkdir -p "$NPM_CONFIG_CACHE"'
                                                sh 'npm install'
                                                sh 'npm run build'
                                            }
                                        } else if (svc.buildType == 'docker-only') {
                                            echo "docker-only: 사전 빌드 스텝 생략"
                                        } else {
                                            error "지원하지 않는 buildType: ${svc.buildType}"
                                        }
                                    }
                                }
                            }

                            stage("Docker Build/Push ${svc.name}") {
                                container('dind') {
                                    withCredentials([
                                        usernamePassword(
                                            credentialsId: config.harborCredentialId,
                                            usernameVariable: 'HARBOR_USER',
                                            passwordVariable: 'HARBOR_PASSWORD'
                                        )
                                    ]) {
                                        sh "docker login -u ${env.HARBOR_USER} -p ${env.HARBOR_PASSWORD} harbor.thisisserver.com"
                                        sh "docker build --network=host -t ${svc.imageRepo}:${env.DOCKER_IMAGE_TAG} -f ${svc.dockerfilePath} ${svc.buildContext}"
                                        sh "docker push ${svc.imageRepo}:${env.DOCKER_IMAGE_TAG}"
                                    }
                                }
                            }
                        }
                    }
                }
            }

            stage('Update Kustomize Image Tags') {
                when {
                    expression { return services.any { it.deployToK8s } }
                }
                steps {
                    script {
                        dir('kubernetes-configs-repo') {
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: "*/${config.k8sConfigsBranch}"]],
                                userRemoteConfigs: [[url: config.k8sConfigsRepoUrl, credentialsId: config.credentialId]],
                                extensions: [
                                    [$class: 'CleanBeforeCheckout'],
                                    [$class: 'LocalBranch', localBranch: config.k8sConfigsBranch]
                                ]
                            ])

                            services.findAll { it.deployToK8s }.each { svc ->
                                def kustomizationFile = "${config.k8sKustomizePathPrefix}/${svc.k8sAppName}/kustomization.yaml"
                                def kustomization = readYaml file: kustomizationFile
                                def imageUpdated = false

                                kustomization.images.each { image ->
                                    if (image.name == svc.imageRepo) {
                                        image.newTag = env.DOCKER_IMAGE_TAG
                                        imageUpdated = true
                                    }
                                }

                                if (!imageUpdated) {
                                    error "이미지 '${svc.imageRepo}'를 ${kustomizationFile}의 images에서 찾지 못했습니다."
                                }

                                writeYaml file: kustomizationFile, data: kustomization, overwrite: true
                                sh "git add ${kustomizationFile}"
                            }

                            sh "git config user.email '${config.jenkinsUserEmail}'"
                            sh "git config user.name '${config.jenkinsUserName}'"
                            sshagent([config.credentialId]) {
                                sh "git commit -m 'Update image tags to ${env.DOCKER_IMAGE_TAG}' || true"
                                sh "git push origin ${config.k8sConfigsBranch}"
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                echo "Pipeline finished."
            }
            success {
                echo "Pipeline succeeded."
                cleanWs()
            }
            failure {
                echo "Pipeline failed."
            }
        }
    }
}
