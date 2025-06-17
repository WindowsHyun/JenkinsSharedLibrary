def call(Map config) {
    if (!config.appName) error "appName 파라미터는 필수입니다."
    if (!config.repoUrl) error "repoUrl 파라미터는 필수입니다."
    if (!config.repoBranch) error "repoBranch 파라미터는 필수입니다."
    if (!config.buildType) error "buildType 파라미터는 필수입니다 (예: 'go', 'docker-only', 'npm')."

    config.dockerRegistry = config.dockerRegistry ?: '192.168.0.201:5000'
    config.k8sConfigsRepoUrl = config.k8sConfigsRepoUrl ?: 'git@github.com:WindowsHyun/kubernetes-configs.git'
    config.k8sConfigsBranch = config.k8sConfigsBranch ?: 'develop'
    config.k8sKustomizePathPrefix = config.k8sKustomizePathPrefix ?: 'apps/dev'
    config.credentialId = config.credentialId ?: 'jenkins-ssh-credential'
    config.jenkinsUserEmail = config.jenkinsUserEmail ?: 'jenkins@thisisserver.com'
    config.jenkinsUserName = config.jenkinsUserName ?: 'Jenkins'
    config.kubernetesAgentLabel = config.kubernetesAgentLabel ?: 'builder'
    config.kubernetesServiceAccount = config.kubernetesServiceAccount ?: 'jenkins-admin'
    config.kubernetesNamespace = config.kubernetesNamespace ?: 'devops'

    def dockerImageName = "${config.dockerRegistry}/${config.appName.toLowerCase()}"
    def k8sKustomizePath = "${config.k8sKustomizePathPrefix}/${config.appName.toLowerCase()}/kustomization.yaml"
    def gitReferenceRepoName = config.repoUrl.split('/')[-1].replace('.git', '')
    def gitReferenceRepo = "/git-reference-repo/${gitReferenceRepoName}.git"


    pipeline {
        agent {
            kubernetes {
                label config.kubernetesAgentLabel
                serviceAccount config.kubernetesServiceAccount
                namespace config.kubernetesNamespace
            }
        }

        environment {
            DOCKER_REGISTRY = "${config.dockerRegistry}"
            DOCKER_IMAGE_NAME = "${dockerImageName}"
            K8S_CONFIGS_REPO_URL = "${config.k8sConfigsRepoUrl}"
            K8S_CONFIGS_BRANCH = "${config.k8sConfigsBranch}"
            K8S_KUSTOMIZE_PATH = "${k8sKustomizePath}"
        }

        stages {
            stage('Checkout Code') {
                steps {
                    echo "Git 저장소 코드 체크아웃 시작: ${config.repoUrl} (${config.repoBranch} 브랜치)"
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: "*/${config.repoBranch}"]],
                        userRemoteConfigs: [[
                            url: config.repoUrl,
                            credentialsId: config.credentialId
                        ]],
                        extensions: [
                            [$class: 'CleanBeforeCheckout'],
                            [$class: 'LocalBranch', localBranch: config.repoBranch],
                            [$class: 'CloneOption',
                                depth: 1,
                                noTags: false,
                                reference: gitReferenceRepo,
                                shallow: false
                            ]
                        ]
                    ])
                    echo "Git 저장소 코드 체크아웃 완료."
                }
            }

            stage('Get Git Commit Hash') {
                steps {
                    script {
                        echo "Git 커밋 해시 가져오기..."
                        env.GIT_COMMIT_HASH = sh(returnStdout: true, script: 'git rev-parse --short=7 HEAD').trim()
                        echo "Current Git Commit Hash: ${env.GIT_COMMIT_HASH}"
                    }
                }
            }

            stage('Build Go Application') {
                when {
                    expression { return config.buildType == 'go' }
                }
                steps {
                    container('go') {
                        echo "Go 애플리케이션 빌드 시작 (go 컨테이너)..."
                        sh 'pwd'
                        sh 'go version'
                        sh 'go mod download'
                        sh "go build -v -o ${config.appName} ."
                    }
                }
            }

            stage('Build Node.js Application') {
                when {
                    expression { return config.buildType == 'npm' }
                }
                steps {
                    container('node') {
                        echo "Node.js 애플리케이션 빌드 시작 (node 컨테이너)..."
                        sh 'npm install'
                        sh 'npm run build'
                    }
                }
            }

            stage('Build and Push Docker Image') {
                steps {
                    container('dind') {
                        echo "Docker 이미지 빌드 및 푸시 시작..."
                        sh "docker build -t ${env.DOCKER_IMAGE_NAME}:${env.GIT_COMMIT_HASH} -f docker/Dockerfile ."
                        sh "docker push ${env.DOCKER_IMAGE_NAME}:${env.GIT_COMMIT_HASH}"
                        echo "Docker Image pushed: ${env.DOCKER_IMAGE_NAME}:${env.GIT_COMMIT_HASH}"

                        echo "Adding 'latest' tag and pushing..."
                        sh "docker tag ${env.DOCKER_IMAGE_NAME}:${env.GIT_COMMIT_HASH} ${env.DOCKER_IMAGE_NAME}:latest"
                        sh "docker push ${env.DOCKER_IMAGE_NAME}:latest"
                        echo "Docker 'latest' tag pushed: ${env.DOCKER_IMAGE_NAME}:latest"
                    }
                }
            }

            stage('Update Kustomize Image Tag') {
                steps {
                    script {
                        echo "Kubernetes configs 저장소 체크아웃 시작: ${config.k8sConfigsRepoUrl} (${config.k8sConfigsBranch} 브랜치)"
                        dir('kubernetes-configs-repo') {
                            checkout([
                                $class: 'GitSCM',
                                branches: [[name: "*/${config.k8sConfigsBranch}"]],
                                userRemoteConfigs: [[
                                    url: config.k8sConfigsRepoUrl,
                                    credentialsId: config.credentialId
                                ]],
                                extensions: [
                                    [$class: 'CleanBeforeCheckout'],
                                    [$class: 'LocalBranch', localBranch: config.k8sConfigsBranch]
                                ]
                            ])
                            
                            echo "kustomization.yaml 파일 업데이트: ${env.K8S_KUSTOMIZE_PATH}"
                            def kustomizationFile = "${env.K8S_KUSTOMIZE_PATH}"
                            def kustomization = readYaml file: kustomizationFile

                            def imageUpdated = false
                            kustomization.images.each { image ->
                                if (image.name == "${env.DOCKER_IMAGE_NAME}") {
                                    image.newTag = env.GIT_COMMIT_HASH
                                    imageUpdated = true
                                    echo "Image tag updated to: ${env.GIT_COMMIT_HASH}"
                                }
                            }

                            if (!imageUpdated) {
                                error "Error: Image '${env.DOCKER_IMAGE_NAME}' not found in ${kustomizationFile}. Please ensure it exists in the 'images' list."
                            }

                            writeYaml file: kustomizationFile, data: kustomization, overwrite: true
                            
                            sh '''
                                mkdir -p ~/.ssh
                                ssh-keyscan -H github.com >> ~/.ssh/known_hosts
                                chmod 644 ~/.ssh/known_hosts
                            '''

                            echo "변경된 kustomization.yaml 커밋 및 푸시..."
                            sh "git config user.email '${config.jenkinsUserEmail}'"
                            sh "git config user.name '${config.jenkinsUserName}'"
                            sshagent([config.credentialId]) {
                                sh "git add ${kustomizationFile}"
                                try {
                                    sh "git commit -m \"Update: ${config.appName} image tag to ${env.GIT_COMMIT_HASH}\""
                                    sh "git push origin ${config.k8sConfigsBranch}"
                                    echo "Successfully committed and pushed kustomization.yaml changes."
                                } catch (Exception e) {
                                    if (e.getMessage().contains('nothing to commit')) {
                                        echo "No changes to commit in kustomization.yaml. Skipping commit and push."
                                    } else {
                                        throw e
                                    }
                                }
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
                echo "Pipeline succeeded! 🎉 Docker Image: ${env.DOCKER_IMAGE_NAME}:${env.GIT_COMMIT_HASH}"
                cleanWs()
            }
            failure {
                echo "Pipeline failed! ❌"
            }
        }
    }
}
