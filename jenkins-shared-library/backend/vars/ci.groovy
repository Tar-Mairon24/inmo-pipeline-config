import hudson.*;

def call(Map params) {
    node('Agent') {
        stage('Set up tools') {
            def goHome = tool name: 'GoLatest', type: 'go'
            def dockerHome = tool name: 'Default', type: 'dockerTool'
            
            // Set PATH first, before running any shell commands
            env.PATH = "${goHome}/bin:${dockerHome}/bin:${env.PATH}"
            
            echo "Setting up golangci-lint..."
            sh 'echo "GOPATH: $(go env GOPATH)"'
            
            sh  "rm -f ${goHome}/bin/golangci-lint || echo 'No existing golangci-lint to remove'"
                
            sh  'curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/HEAD/install.sh | sh -s -- -b $(go env GOPATH)/bin v2.3.1'

            def goPath = sh(script: 'go env GOPATH', returnStdout: true).trim()
            env.PATH = "${goPath}/bin:${env.PATH}:${dockerHome}/bin"
            
            echo "=== Tool Setup Complete ==="

            sh '''
                echo "=== Tool Verification ==="
                echo "Go version: $(go version)"
                echo "Docker version: $(docker --version)"
                echo "Docker Compose version: $(docker compose version || docker-compose --version)"
                echo "golangci-lint version: $(golangci-lint --version)"
                echo "PATH: $PATH"
                echo "GOPATH: $(go env GOPATH)"
            '''
        }

        stage('Parameters') {
            sh 'whoami'

            def pipelineKind = utils.getPipelineKind()

            if (pipelineKind == 'pr') {
                env.BRANCH_NAME_TARGET = env.CHANGE_TARGET
                env.BRANCH_NAME_ORIGIN = env.CHANGE_BRANCH
            } else if (pipelineKind == 'branch') {
                env.BRANCH_NAME_TARGET = env.BRANCH_NAME
                env.BRANCH_NAME_ORIGIN = env.BRANCH_NAME
            } else {
                error "Unsupported PipelineKind: ${pipelineKind}"
            }

            env.ENVIRONMENT_NAME = utils.getEnviromentName(env.BRANCH_NAME_TARGET)

            echo "BRANCH_NAME_TARGET: $BRANCH_NAME_TARGET"
            echo "BRANCH_NAME_ORIGIN: $BRANCH_NAME_ORIGIN"
            echo "ENVIRONMENT_NAME: $ENVIRONMENT_NAME"
        }

        stage('Checkout SCM') {
            checkout scm

            echo "Checked out branch: ${env.BRANCH_NAME}"
            sh '''
                echo "Current branch: $(git rev-parse --abbrev-ref HEAD)"
                echo "Last commit: $(git log -1 --pretty=format:'%h - %s')"
                ls -la
            '''
        }

        stage('Load configuration') {
            checkout([
                $class: 'GitSCM',
                branches: [[name: 'main']],
                userRemoteConfigs: [[
                    url: 'https://github.com/Tar-Mairon24/inmo-pipeline-properties.git',
                    credentialsId: 'Github_Token'
                ]],
                extensions: [
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: 'config-repo']
                ]
            ])

                echo "Configuration repository checked out."
                sh 'ls -la'
                echo "Configuration files from config-repo/:"
                sh 'ls -la config-repo/'
        }

        stage('Parsing configuration') {
            script {
                def configDir = "config-repo/backend/${env.ENVIRONMENT_NAME}"
                def success = utils.toml2env(configDir)
                if (!success) {
                    error "Failed to parse configuration from ${configDir}"
                }
                sh 'ls -la'
            }
        }

        stage('Prepare Go Modules') {
            echo "Downloading Go module dependencies..."
            sh '''
                go mod download
                go mod tidy
            '''
        }

        stage('Security Scan') {
            echo "Running vulnerability scan..."
            def vulnResult = sh(
                script: 'go run golang.org/x/vuln/cmd/govulncheck@latest ./...',
                returnStatus: true
            )
            if (vulnResult != 0) {
                error 'Vulnerability scan found issues! Please review and fix before proceeding.'
            }
            echo 'Vulnerability scan passed successfully.'
        }

        stage('Test') {
            echo "Running tests..."
            
            sh '''
                echo "=== Pre-test checks ==="
                echo "Available packages:"
                go list ./...
                echo "Test files:"
                find . -name "*_test.go"
            '''
            
            def testResult = sh(
                script: '''
                    # Run tests with proper coverage
                    go test -v -race -coverprofile=coverage.out -covermode=atomic ./...
                ''',
                returnStatus: true
            )
            
            sh '''
                echo "=== Post-test checks ==="
                ls -la coverage.*
                
                if [ -f coverage.out ] && [ -s coverage.out ]; then
                    echo "Coverage file generated successfully"
                    echo "Total lines in coverage.out: $(wc -l < coverage.out)"
                    
                    # Generate HTML report
                    go tool cover -html=coverage.out -o coverage.html
                    
                    # Show coverage summary
                    echo "=== Coverage Summary ==="
                    go tool cover -func=coverage.out | tail -10
                else
                    echo "ERROR: Coverage file is empty or missing"
                    touch coverage.html  # Create empty file to prevent pipeline failure
                fi
            '''

            archiveArtifacts artifacts: 'coverage.html,coverage.out', allowEmptyArchive: true

            if (testResult != 0) {
                error 'Tests failed! Check the coverage report for details.'
            }
            echo 'Tests completed successfully.'
        }

        stage('Lint') {
            echo "Running linter..."
            sh 'golangci-lint run ./...'
        }

        stage('Dockerfile Setup') {
            checkout([
                $class: 'GitSCM',
                branches: [[name: 'main']],
                userRemoteConfigs: [[
                    url: 'https://github.com/Tar-Mairon24/inmo-pipeline-config.git',
                    credentialsId: 'Github_Token'
                ]],
                extensions: [
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: 'dockerfiles-repo']
                ]
            ])

            echo "Dockerfiles repository checked out."
            sh '''
                ls -la
                echo ""
                echo "Dockerfiles-repo/:"
                ls -la dockerfiles-repo/dockerfiles/archetypes/backend/
            '''
            echo "Copying Dockerfile to project root..."
            sh '''
                cp dockerfiles-repo/dockerfiles/archetypes/backend/Dockerfile .
                if [ ! -f Dockerfile ]; then
                    echo "Error: Dockerfile not found after copy."
                    exit 1
                fi
                echo "Dockerfile copied successfully."
            '''
            echo "Dockerfile setup completed."
            sh '''
                ls -la Dockerfile
                rm -rf dockerfiles-repo || echo "No dockerfiles-repo directory to remove"
                rm -rf dockerfiles-repo@* || echo "No dockerfiles-repo@tmp directory to remove"
                ls -la
            '''
        }

        stage("Build Image") {
            script {
                def gitCommit = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                def imageName = "inmo-backend"
                def imageTag = "${imageName}:${gitCommit}"
                
                echo "Building Docker image with commit hash: ${gitCommit}"
                echo "Git commit: ${gitCommit}"
                echo "Build number: ${env.BUILD_NUMBER}"

                sh "docker build -t ${imageTag} -t ${imageName}:latest ."
                    
                echo "Docker image built successfully with tags:"
                echo "  - ${imageTag} (archived version)"
                echo "  - ${imageName}:latest (current version)"
                    
                sh "docker images | grep ${imageName}"

                env.DOCKER_IMAGE_TAG = gitCommit
            }
        }

        stage("Push to Registry") {
            def imageName = "inmo-backend"
            def gitCommit = env.DOCKER_IMAGE_TAG
            def dockerHubRepo = "tarmairon24/${imageName}"

            withCredentials([usernamePassword(credentialsId: 'docker_hub', 
                                            usernameVariable: 'DOCKERHUB_USERNAME', 
                                            passwordVariable: 'DOCKERHUB_PASSWORD')]) {
                sh '''
                    echo "$DOCKERHUB_PASSWORD" | docker login -u "$DOCKERHUB_USERNAME" --password-stdin
                '''
            }

            def imageTag = "${dockerHubRepo}:${gitCommit}"
            def latestTag = "${dockerHubRepo}:latest"
            
            echo "Tagging Docker images to Docker Hub:"
            sh """
                docker tag ${imageName}:${gitCommit} ${imageTag}
                docker tag ${imageName}:latest ${latestTag}
            """
            echo "Pushing Docker images to Docker Hub:"
            sh """
                docker push ${imageTag}
                docker push ${latestTag}
            """
            echo "Docker images pushed successfully:"
            echo "  - ${dockerHubRepo}:${gitCommit}"
            echo "  - ${dockerHubRepo}:latest"

            sh 'docker logout'
        }

        stage('Docker Cleanup') {
            script {
                def imageName = "inmo-backend"
                utils.dockerCleanup(imageName)
            }
        }

        stage('Post declarative action') {
            echo "Cleaning up temporary files..."
            sh 'rm -f app.toml .env'
            sh 'rm -rf config-repo'
            echo "Cleanup completed"
        }
    }
}
