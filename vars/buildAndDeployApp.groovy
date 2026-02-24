def call(Map config) {
    if (!config.repoUrl) error "repoUrl 파라미터는 필수입니다."
    if (!config.repoBranch) error "repoBranch 파라미터는 필수입니다."

    config.dockerRegistry = config.dockerRegistry ?: 'harbor.thisisserver.com/library'
    config.k8sConfigsRepoUrl = config.k8sConfigsRepoUrl ?: 'git@github.com:WindowsHyun/kubernetes-configs.git'
    config.k8sConfigsBranch = config.k8sConfigsBranch ?: 'develop'
    config.k8sKustomizePathPrefix = config.k8sKustomizePathPrefix ?: 'apps/dev'
    config.k8sKustomizationFile = config.k8sKustomizationFile ?: ''
    config.credentialId = config.credentialId ?: 'jenkins-ssh-credential'
    config.jenkinsUserEmail = config.jenkinsUserEmail ?: 'jenkins@thisisserver.com'
    config.jenkinsUserName = config.jenkinsUserName ?: 'Jenkins'
    config.kubernetesAgentLabel = config.kubernetesAgentLabel ?: 'builder-k3s'
    config.kubernetesServiceAccount = config.kubernetesServiceAccount ?: 'jenkins-admin'
    config.kubernetesNamespace = config.kubernetesNamespace ?: 'devops'
    config.kubernetesCloud = config.kubernetesCloud ?: 'k3s'
    config.harborCredentialId = config.get('harborCredentialId', '')
    config.harborUserCredentialId = config.harborUserCredentialId ?: 'HARBOR_USER'
    config.harborPasswordCredentialId = config.harborPasswordCredentialId ?: 'HARBOR_PASSWORD'
    config.deployToK8s = config.get('deployToK8s', true)
    def harborRegistryHost = (config.dockerRegistry ?: '').tokenize('/')[0]

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
            k8sAppName: (config.appName ?: '').toLowerCase(),
            k8sKustomizationFile: config.k8sKustomizationFile ?: ''
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
        svc.k8sKustomizationFile = svc.k8sKustomizationFile ?: config.k8sKustomizationFile
        svc.k8sDeploymentName = svc.k8sDeploymentName ?: ''
        svc.k8sChangeCausePatchFile = svc.k8sChangeCausePatchFile ?: ''
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
                                                sh 'go mod download'
                                                sh "go build -v -o ${env.WORKSPACE}/${svc.name} ."
                                                sh "ls -lh ${env.WORKSPACE}/${svc.name}"
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
                                    if (config.harborCredentialId?.trim()) {
                                        withCredentials([
                                            usernamePassword(
                                                credentialsId: config.harborCredentialId,
                                                usernameVariable: 'HARBOR_USER',
                                                passwordVariable: 'HARBOR_PASSWORD'
                                            )
                                        ]) {
                                            sh "docker login -u ${env.HARBOR_USER} -p ${env.HARBOR_PASSWORD} ${harborRegistryHost}"
                                            sh "docker build --network=host -t ${svc.imageRepo}:${env.DOCKER_IMAGE_TAG} -f ${svc.dockerfilePath} ${svc.buildContext}"
                                            sh "docker push ${svc.imageRepo}:${env.DOCKER_IMAGE_TAG}"
                                        }
                                    } else {
                                        withCredentials([
                                            string(credentialsId: config.harborUserCredentialId, variable: 'HARBOR_USER'),
                                            string(credentialsId: config.harborPasswordCredentialId, variable: 'HARBOR_PASSWORD')
                                        ]) {
                                            sh "docker login -u ${env.HARBOR_USER} -p ${env.HARBOR_PASSWORD} ${harborRegistryHost}"
                                            sh "docker build --network=host -t ${svc.imageRepo}:${env.DOCKER_IMAGE_TAG} -f ${svc.dockerfilePath} ${svc.buildContext}"
                                            sh "docker push ${svc.imageRepo}:${env.DOCKER_IMAGE_TAG}"
                                        }
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

                            def resolveKustomizationFile = { svc ->
                                if (svc.k8sKustomizationFile?.trim()) {
                                    return svc.k8sKustomizationFile.trim()
                                }

                                def defaultByAppName = "${config.k8sKustomizePathPrefix}/${svc.k8sAppName}/kustomization.yaml"
                                if (fileExists(defaultByAppName)) {
                                    return defaultByAppName
                                }

                                def discovered = sh(
                                    returnStdout: true,
                                    script: """for file in \$(find ${config.k8sKustomizePathPrefix} -maxdepth 5 -name kustomization.yaml 2>/dev/null); do
  if grep -Fq \"name: ${svc.imageRepo}\" \"\$file\"; then
    echo \"\$file\"
  fi
done"""
                                ).trim().readLines().findAll { it?.trim() }

                                if (discovered.size() == 1) {
                                    return discovered[0].trim()
                                }

                                def availableKustomizations = sh(
                                    returnStdout: true,
                                    script: "find ${config.k8sKustomizePathPrefix} -maxdepth 5 -name kustomization.yaml 2>/dev/null | sort || true"
                                ).trim()

                                if (discovered.size() > 1) {
                                    error """서비스 '${svc.name}'의 대상 kustomization 파일이 여러 개로 모호합니다.
imageRepo: ${svc.imageRepo}
발견된 파일:
${discovered.join('\n')}

services[].k8sKustomizationFile로 명시해 주세요."""
                                }

                                error """kustomization 파일을 찾지 못했습니다 (service=${svc.name}, imageRepo=${svc.imageRepo})
확인할 항목:
- services[].k8sAppName
- config.k8sKustomizePathPrefix
- config.k8sKustomizationFile / services[].k8sKustomizationFile

${config.k8sKustomizePathPrefix} 하위에서 찾은 kustomization.yaml:
${availableKustomizations ?: '(없음)'}"""
                            }

                            def deployServices = services.findAll { it.deployToK8s }
                            deployServices.each { svc ->
                                svc._resolvedKustomizationFile = resolveKustomizationFile(svc)
                            }

                            def serviceGroups = deployServices.groupBy { it._resolvedKustomizationFile }

                            serviceGroups.each { kustomizationFile, groupedServices ->
                                if (!fileExists(kustomizationFile)) {
                                    error "해결된 kustomization 파일이 존재하지 않습니다: ${kustomizationFile}"
                                }

                                def kustomization = readYaml file: kustomizationFile

                                if (!(kustomization?.images instanceof List)) {
                                    error "${kustomizationFile}에 images 항목이 없거나 형식이 올바르지 않습니다."
                                }

                                groupedServices.each { svc ->
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
                                }

                                writeYaml file: kustomizationFile, data: kustomization, overwrite: true
                                sh "git add ${kustomizationFile}"
                            }

                            deployServices.findAll { it.k8sChangeCausePatchFile?.trim() }.each { svc ->
                                def patchFile = svc.k8sChangeCausePatchFile.trim()

                                if (!fileExists(patchFile)) {
                                    error "change-cause 패치 파일을 찾지 못했습니다: ${patchFile}"
                                }

                                def patchData = readYaml file: patchFile
                                def deploymentName = svc.k8sDeploymentName?.trim() ? svc.k8sDeploymentName.trim() : "${svc.k8sAppName}-deployment"

                                if (patchData?.kind != 'Deployment' || patchData?.metadata?.name != deploymentName) {
                                    error "${patchFile}의 Deployment 이름이 예상값과 다릅니다. expected=${deploymentName}, actual=${patchData?.metadata?.name}"
                                }

                                if (!patchData.metadata.annotations) {
                                    patchData.metadata.annotations = [:]
                                }

                                patchData.metadata.annotations['kubernetes.io/change-cause'] = "Hash: ${env.GIT_COMMIT_SHORT_HASH}, Log: Change: ${env.GIT_COMMIT_MESSAGE_RAW}"
                                writeYaml file: patchFile, data: patchData, overwrite: true
                                sh "git add ${patchFile}"
                            }

                            sh "git config user.email '${config.jenkinsUserEmail}'"
                            sh "git config user.name '${config.jenkinsUserName}'"

                            def hasStagedChanges = sh(returnStatus: true, script: 'git diff --cached --quiet') != 0
                            if (hasStagedChanges) {
                                sh "git commit -m \"Change: update image tags to ${env.DOCKER_IMAGE_TAG} (${env.JOB_NAME}#${env.BUILD_NUMBER})\""
                                sshagent([config.credentialId]) {
                                    sh "export GIT_SSH_COMMAND='ssh -v -o StrictHostKeyChecking=no' && git push origin ${config.k8sConfigsBranch}"
                                }
                            } else {
                                echo "커밋할 변경사항이 없어 git push를 생략합니다."
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
