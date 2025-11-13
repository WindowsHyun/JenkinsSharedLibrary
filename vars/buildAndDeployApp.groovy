def call(Map config) {
    // 필수 파라미터 검사
    if (!config.appName) error "appName 파라미터는 필수입니다."
    if (!config.repoUrl) error "repoUrl 파라미터는 필수입니다."
    if (!config.repoBranch) error "repoBranch 파라미터는 필수입니다."
    if (!config.buildType) error "buildType 파라미터는 필수입니다 (예: 'go', 'npm', 'nextjs', 'docker-only')."

    // 기본값 설정
    config.dockerRegistry = config.dockerRegistry ?: 'harbor.thisisserver.com/library'
    config.dockerfilePath = config.dockerfilePath ?: 'Dockerfile' // Dockerfile 경로를 설정할 수 있도록 추가
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
    config.deploymentStrategy = config.deploymentStrategy ?: 'standard'
    config.enableSonarQube = config.get('enableSonarQube', false) 
    config.sonarqubeServer = config.get('sonarqubeServer', 'JenkinsSonarqube') 
    config.sonarqubeScanner = config.get('sonarqubeScanner', 'JenkinsSonarqube')
    config.harborCredentialId = config.harborCredentialId ?: 'harbor'
    config.harborHostAliasIp = config.harborHostAliasIp ?: '192.168.0.201'
    config.harborImagePullSecret = config.harborImagePullSecret ?: 'harbor-registry-secret' // Harbor 이미지 pull을 위한 Kubernetes Secret 이름

    // 파이프라인에서 사용할 변수 정의
    def dockerImageName = "${config.dockerRegistry}/${config.appName.toLowerCase()}"
    def targetAppName = config.appName.toLowerCase()
    if (config.deploymentStrategy == 'blue-green') {
        targetAppName = "${targetAppName}/green"
        echo "Blue/Green 배포 전략이 감지되었습니다. Green 환경에 배포합니다. Target: ${targetAppName}"
    }
    def k8sKustomizePath = "${config.k8sKustomizePathPrefix}/${targetAppName}/kustomization.yaml"
    def gitReferenceRepoName = config.repoUrl.split('/')[-1].replace('.git', '')
    def gitReferenceRepo = "/git-reference-repo/${gitReferenceRepoName}.git"

    pipeline {
        agent {
            kubernetes {
                cloud config.kubernetesCloud
                inheritFrom config.kubernetesAgentLabel
                serviceAccount config.kubernetesServiceAccount
                namespace config.kubernetesNamespace
                yaml """
apiVersion: v1
kind: Pod
metadata:
  annotations:
    linkerd.io/inject: disabled
spec:
  securityContext:
    runAsUser: 0
    # runAsGroup: 1000
    # fsGroup: 1000
  hostAliases:
  - ip: "${config.harborHostAliasIp}"
    hostnames:
    - "harbor.thisisserver.com"
  imagePullSecrets:
  - name: ${config.harborImagePullSecret}
"""
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

            stage('Get Git Commit Hash and Message') {
                steps {
                    script {
                        echo "Git 커밋 정보 가져오기..."
                        env.GIT_COMMIT_SHORT_HASH = sh(returnStdout: true, script: 'git rev-parse --short=7 HEAD').trim()
                        env.GIT_COMMIT_FULL_HASH = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        env.GIT_COMMIT_MESSAGE_RAW = sh(returnStdout: true, script: 'git log -1 --pretty=%s').trim()
                        
                        // 이미지 태그 생성: YYYYMMDD_빌드번호
                        def dateTime = sh(returnStdout: true, script: 'date +%Y%m%d').trim()
                        env.DOCKER_IMAGE_TAG = "${dateTime}_${env.BUILD_NUMBER}"

                        echo "Current Git Short Commit Hash: ${env.GIT_COMMIT_SHORT_HASH}"
                        echo "Current Git Full Commit Hash: ${env.GIT_COMMIT_FULL_HASH}"
                        echo "Current Git Commit Message (Subject): ${env.GIT_COMMIT_MESSAGE_RAW}"
                        echo "Docker Image Tag: ${env.DOCKER_IMAGE_TAG}"
                    }
                }
            }

            stage('Build Go Application') {
                when {
                    expression { return config.buildType == 'go' }
                }
                steps {
                    container('jnlp') {
                        echo "Go 애플리케이션 빌드 시작 (jnlp 컨테이너)..."
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
                    container('jnlp') {
                        echo "Node.js 애플리케이션 빌드 시작 (jnlp 컨테이너)..."
                        sh 'npm install'
                        sh 'npm run build'
                    }
                }
            }

            // --- ✨ 새로 추가된 Next.js 빌드 스테이지 ---
            stage('Build Next.js Application') {
                when {
                    expression { return config.buildType == 'nextjs' }
                }
                steps {
                    container('jnlp') {
                        echo "Next.js 애플리케이션 빌드 시작 (jnlp 컨테이너)..."
                        sh 'npm install'
                        sh 'npm run build'
                        sh 'chown -R 1000:1000 .next'
                    }
                }
            }

            stage('SonarQube Static Analysis') {
                when {
                    expression { return config.enableSonarQube }
                }
                steps {
                    script {
                        echo "SonarQube 분석을 시작합니다..."
                        def sonarScannerHome = tool name: config.sonarqubeScanner, type: 'hudson.plugins.sonar.SonarRunnerInstallation'
                        container('jnlp') {
                            withSonarQubeEnv(config.sonarqubeServer) {
                                def sonarParams = [
                                    "-Dsonar.projectKey=${config.appName}",
                                    "-Dsonar.projectName=${config.appName}",
                                    "-Dsonar.sources=.",
                                    "-Dsonar.host.url=${SONAR_HOST_URL}",
                                    "-Dsonar.token=${SONAR_AUTH_TOKEN}"
                                ]

                                if (config.buildType == 'go') {
                                    if (fileExists('coverage.out')) {
                                        sonarParams.add("-Dsonar.go.coverage.reportPaths=coverage.out")
                                    }
                                } else if (config.buildType == 'npm' || config.buildType == 'nextjs') {
                                    if (fileExists('coverage/lcov.info')) {
                                        sonarParams.add("-Dsonar.javascript.lcov.reportPaths=coverage/lcov.info")
                                    }
                                }
                                sh "${sonarScannerHome}/bin/sonar-scanner ${sonarParams.join(' ')}"
                            }
                        }
                    }
                }
            }

            stage('Verify Build Artifacts') {
                steps {
                    echo "Verifying build artifacts..."
                    sh 'ls -lah' 
                }
            }

            stage('Login to Harbor Registry') {
                steps {
                    container('jnlp') {
                        script {
                            echo "Harbor 레지스트리에 로그인 중..."
                            def harborHost = "harbor.thisisserver.com"
                            def harborIp = config.harborHostAliasIp
                            
                            // 헤어핀 문제 해결: 컨테이너 내부 /etc/hosts 확인
                            echo "Harbor 호스트 설정 확인 중..."
                            sh """
                                echo "현재 /etc/hosts의 harbor.thisisserver.com 설정:"
                                grep harbor.thisisserver.com /etc/hosts || echo "harbor.thisisserver.com이 /etc/hosts에 없습니다"
                                
                                # /etc/hosts 확인 (Pod의 hostAliases가 이미 설정되어 있음)
                                if ! grep -q "${harborIp}.*harbor.thisisserver.com" /etc/hosts; then
                                    echo "경고: /etc/hosts에 올바른 Harbor IP가 설정되지 않았습니다"
                                    echo "Pod의 hostAliases 설정을 확인하세요"
                                else
                                    echo "/etc/hosts 설정이 올바릅니다"
                                fi
                                
                                # 네트워크 연결 테스트
                                echo "Harbor 서버 연결 테스트 중..."
                                echo "Harbor IP로 직접 연결 테스트: ${harborIp}"
                                ping -c 1 ${harborIp} || echo "ping 실패 (정상일 수 있음)"
                                echo "Harbor 호스트명으로 연결 테스트: ${harborHost}"
                                ping -c 1 ${harborHost} || echo "ping 실패 (정상일 수 있음)"
                                curl -k -I https://${harborHost} 2>&1 | head -5 || echo "HTTPS 연결 테스트 완료"
                            """
                            
                            // docker 또는 podman 중 사용 가능한 것을 확인
                            def dockerCmd = sh(returnStdout: true, script: 'which docker || which podman || echo "none"').trim()
                            
                            if (dockerCmd == "none") {
                                error "docker 또는 podman이 설치되어 있지 않습니다."
                            }
                            
                            // withCredentials를 사용하여 크리덴셜 직접 바인딩
                            withCredentials([
                                string(credentialsId: 'HARBOR_USER', variable: 'HARBOR_USER'),
                                string(credentialsId: 'HARBOR_PASSWORD', variable: 'HARBOR_PASSWORD')
                            ]) {
                                // Harbor API로 크리덴셜 검증
                                echo "Harbor API로 크리덴셜 검증 중..."
                                def apiTest = sh(
                                    script: """
                                        curl -k -u "\$HARBOR_USER:\$HARBOR_PASSWORD" https://${harborHost}/api/v2.0/projects 2>&1 | head -10
                                    """,
                                    returnStatus: true
                                )
                                
                                if (apiTest != 0) {
                                    error """
Harbor API 호출 실패! 크리덴셜이 올바르지 않을 수 있습니다.

가능한 원인:
1. Jenkins credentials에 저장된 값이 잘못되었을 수 있습니다.
   - HARBOR_USER credential ID 확인 필요
   - HARBOR_PASSWORD credential ID 확인 필요

2. Harbor robot 계정이 비활성화되었거나 권한이 변경되었을 수 있습니다.
"""
                                }
                                
                                echo "Harbor API 호출 성공! 크리덴셜이 올바릅니다."
                                echo "podman 인증 파일을 생성합니다..."
                                
                                // podman 인증 파일 직접 생성 (여러 위치에 생성하여 호환성 확보)
                                sh """
                                    # podman 인증 파일 경로 설정 (여러 위치)
                                    AUTH_DIR1="\${HOME}/.config/containers"
                                    AUTH_FILE1="\${AUTH_DIR1}/auth.json"
                                    AUTH_DIR2="/root/.config/containers"
                                    AUTH_FILE2="\${AUTH_DIR2}/auth.json"
                                    
                                    # Base64 인코딩된 인증 정보 생성
                                    AUTH_B64=\$(echo -n "\$HARBOR_USER:\$HARBOR_PASSWORD" | base64 | tr -d '\n')
                                    
                                    # 인증 파일 생성 함수
                                    create_auth_file() {
                                        local AUTH_FILE=\$1
                                        local AUTH_DIR=\$(dirname "\$AUTH_FILE")
                                        mkdir -p "\$AUTH_DIR"
                                        
                                        # podman 인증 파일 형식으로 생성 (JSON 형식)
                                        echo '{' > "\$AUTH_FILE"
                                        echo '  "auths": {' >> "\$AUTH_FILE"
                                        echo '    "'${harborHost}'": {' >> "\$AUTH_FILE"
                                        echo '      "auth": "'"\${AUTH_B64}"'"' >> "\$AUTH_FILE"
                                        echo '    }' >> "\$AUTH_FILE"
                                        echo '  }' >> "\$AUTH_FILE"
                                        echo '}' >> "\$AUTH_FILE"
                                        
                                        chmod 600 "\$AUTH_FILE"
                                        echo "인증 파일 생성 완료: \$AUTH_FILE"
                                    }
                                    
                                    # 여러 위치에 인증 파일 생성
                                    create_auth_file "\${AUTH_FILE1}"
                                    create_auth_file "\${AUTH_FILE2}"
                                    
                                    # 환경 변수 설정 (podman이 인증 파일을 찾을 수 있도록)
                                    export REGISTRY_AUTH_FILE="\${AUTH_FILE1}"
                                    export XDG_RUNTIME_DIR="\${HOME}/.config"
                                    
                                    # 인증 파일이 올바르게 생성되었는지 확인
                                    if [ -f "\${AUTH_FILE1}" ] && [ -f "\${AUTH_FILE2}" ]; then
                                        echo "인증 파일 생성 성공!"
                                        echo "인증 파일 위치:"
                                        echo "  - \${AUTH_FILE1}"
                                        echo "  - \${AUTH_FILE2}"
                                        echo "환경 변수 설정:"
                                        echo "  - REGISTRY_AUTH_FILE=\${AUTH_FILE1}"
                                    else
                                        echo "인증 파일 생성 실패!"
                                        exit 1
                                    fi
                                """
                                
                                echo "Harbor 레지스트리 인증 설정 완료!"
                            }
                        }
                    }
                }
            }

            stage('Build and Push Docker Image') {
                steps {
                    container('jnlp') {
                        script {
                            echo "Docker 이미지 빌드 및 푸시 시작..."
                            def dockerCmd = sh(returnStdout: true, script: 'which docker || which podman || echo "docker"').trim()
                            
                            // 인증 파일 확인 및 환경 변수 설정
                            sh """
                                # 인증 파일 경로 확인
                                AUTH_FILE1="\${HOME}/.config/containers/auth.json"
                                AUTH_FILE2="/root/.config/containers/auth.json"
                                
                                # 인증 파일이 존재하는지 확인
                                if [ -f "\${AUTH_FILE1}" ]; then
                                    echo "인증 파일 확인: \${AUTH_FILE1}"
                                    export REGISTRY_AUTH_FILE="\${AUTH_FILE1}"
                                elif [ -f "\${AUTH_FILE2}" ]; then
                                    echo "인증 파일 확인: \${AUTH_FILE2}"
                                    export REGISTRY_AUTH_FILE="\${AUTH_FILE2}"
                                else
                                    echo "경고: 인증 파일을 찾을 수 없습니다!"
                                    echo "인증 파일 위치 확인:"
                                    ls -la \${HOME}/.config/containers/ 2>/dev/null || echo "  \${HOME}/.config/containers/ 없음"
                                    ls -la /root/.config/containers/ 2>/dev/null || echo "  /root/.config/containers/ 없음"
                                fi
                                
                                # 환경 변수 출력 (디버깅)
                                echo "REGISTRY_AUTH_FILE=\${REGISTRY_AUTH_FILE}"
                            """
                            
                            // 이미지 빌드
                            sh "${dockerCmd} build --network=host -t ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG} -f ${config.dockerfilePath} ."
                            
                            // 이미지 푸시 (환경 변수와 함께)
                            sh """
                                export REGISTRY_AUTH_FILE="\${HOME}/.config/containers/auth.json"
                                if [ ! -f "\${REGISTRY_AUTH_FILE}" ]; then
                                    export REGISTRY_AUTH_FILE="/root/.config/containers/auth.json"
                                fi
                                
                                echo "인증 파일 경로: \${REGISTRY_AUTH_FILE}"
                                ${dockerCmd} push ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}
                            """
                            
                            echo "Docker Image pushed: ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}"
                        }
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
                                    image.newTag = env.DOCKER_IMAGE_TAG
                                    imageUpdated = true
                                    echo "Image tag updated to: ${env.DOCKER_IMAGE_TAG}"
                                }
                            }

                            if (!imageUpdated) {
                                error "Error: Image '${env.DOCKER_IMAGE_NAME}' not found in ${kustomizationFile}. Please ensure it exists in the 'images' list."
                            }

                            def kustomizationDir = kustomizationFile.substring(0, kustomizationFile.lastIndexOf('/'))
                            def patchFile = "${kustomizationDir}/patch-change-cause.yaml"

                            if (fileExists(patchFile)) {
                                def patchContent = readFile(patchFile)
                                def maxMessageLength = 60
                                def commitMessageForCause = env.GIT_COMMIT_MESSAGE_RAW
                                if (commitMessageForCause.length() > maxMessageLength) {
                                    commitMessageForCause = commitMessageForCause.substring(0, maxMessageLength - 3) + "..."
                                }
                                def changeCauseValue = "Hash: ${env.GIT_COMMIT_FULL_HASH}, Log: ${commitMessageForCause}"
                                def pattern = ~/kubernetes\.io\/change-cause:\s*'[^']*'/
                                def updatedPatchContent = patchContent.replaceAll(pattern, "kubernetes.io/change-cause: '${changeCauseValue}'")
                                writeFile file: patchFile, text: updatedPatchContent
                                echo "CHANGE-CAUSE annotation updated in patch file to: ${changeCauseValue}"
                            } else {
                                echo "patch-change-cause.yaml not found. Skipping change-cause update."
                            }

                            writeYaml file: kustomizationFile, data: kustomization, overwrite: true
                            withCredentials([string(credentialsId: 'github-known-host', variable: 'GITHUB_HOST_KEY')]) {
                                sh '''
                                    mkdir -p ~/.ssh
                                    echo "${GITHUB_HOST_KEY}" > ~/.ssh/known_hosts
                                    chmod 644 ~/.ssh/known_hosts
                                '''
                            }

                            echo "변경된 kustomization.yaml 커밋 및 푸시..."
                            sh "git config user.email '${config.jenkinsUserEmail}'"
                            sh "git config user.name '${config.jenkinsUserName}'"
                            sshagent([config.credentialId]) {
                                sh "git add ${kustomizationFile}"
                                if (fileExists(patchFile)) {
                                    sh "git add ${patchFile}"
                                }
                                try {
                                    sh "git commit -m \"Update: ${config.appName} image tag to ${env.DOCKER_IMAGE_TAG}\""
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
                echo "Pipeline succeeded! 🎉 Docker Image: ${env.DOCKER_IMAGE_NAME}:${env.DOCKER_IMAGE_TAG}"
                cleanWs()
            }
            failure {
                echo "Pipeline failed! ❌"
            }
        }
    }
}
