import hudson.*;

def call(Map params) {
    node('Agent') {
        stage('Set up tools') {
            def goHome = tool name: 'GoLatest', type: 'go'
            def dockerHome = tool name: 'Default', type: 'dockerTool'
            
            // Set PATH first, before running any shell commands
            env.PATH = "${goHome}/bin:${dockerHome}/bin:${env.PATH}"
            
            sh """
                echo "Setting up golangci-lint..."
                echo "GOPATH: $(go env GOPATH)"
                
                # Remove existing golangci-lint if present
                rm -f ${goHome}/bin/golangci-lint || echo "No existing golangci-lint to remove"
                
                curl -sSfL https://raw.githubusercontent.com/golangci/golangci-lint/HEAD/install.sh | sh -s -- -b $(go env GOPATH)/bin v2.3.1
                # Verify installation
                which golangci-lint || echo "golangci-lint installation failed"
                golangci-lint --version || echo "golangci-lint version check failed"
                """
                
                // Update PATH to include GOPATH/bin first (in case it's not already there)
                def goPath = sh(script: 'go env GOPATH', returnStdout: true).trim()
                env.PATH = "${goPath}/bin:${env.PATH}"
                
                sh '''
                    echo "Tool verification:"
                    echo "Go version: $(go version)"
                    echo "Docker version: $(docker --version)"
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

            sh '''
                echo "BRANCH_NAME_TARGET: $BRANCH_NAME_TARGET"
                echo "BRANCH_NAME_ORIGIN: $BRANCH_NAME_ORIGIN"
                echo "ENVIRONMENT_NAME: $ENVIRONMENT_NAME"
            '''
        }

        stage('Checkout SCM') {
            checkout scm

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

            sh '''
                echo "Configuration repository checked out."
                ls -la
                echo ""
                echo "Configuration files from config-repo/:"
                ls -la config-repo/
            '''
        }

        stage('Parsing configuration') {
            script {
                def configDir = "config-repo/backend/${env.ENVIRONMENT_NAME}"
                def configFile = "${configDir}/app.toml"

                sh """
                    echo "Looking for configuration: ${configFile}"
                    if [ ! -f "${configFile}" ]; then
                        echo "Configuration file not found: ${configFile}"
                        echo "Available backend configurations:"
                        find config-repo/backend/ -name "*.toml" || echo "No TOML files found"
                        exit 1
                    fi
                    echo "Found configuration file"
                    echo "Content preview:"
                    head -20 "${configFile}"
                """

                sh """
                    echo "Converting TOML to .env format..."
                    cp "${configFile}" config-repo/tools/app.toml
                    if [ ! -f "config-repo/tools/toml2env.go" ]; then
                        echo "❌ Converter not found: config-repo/tools/toml2env.go"
                        echo "Available files in tools directory:"
                        ls -la config-repo/tools/ || echo "Tools directory not found"
                        exit 1
                    fi
                    ls -la
                    ls -la config-repo/tools 
                    echo "Running conversion..."
                    cd config-repo/tools/
                    go run toml2env.go app.toml ../../.env
                    echo "Conversion complete. Generated .env file:"
                    cd ../../
                    ls -la
                    if [ ! -f .env ]; then
                        echo "Error: .env file not generated."
                        exit 1
                    fi
                    echo "Environment variables loaded from .env file."
                    echo "Conversion complete"
                    echo "Environment variables loaded (\$(wc -l < .env) variables):"
                    head -10 .env
                    cat .env
                """

                sh '''
                    echo "Removing config-repo to avoid Go modules issues..."
                    rm -rf config-repo || echo "No config-repo directory to remove"
                    rm -rf config-repo@* || echo "No config-repo@temp directory to remove"
                    ls -la
                '''
            }
        }

        stage('Prepare Go Modules') {
            sh '''
                echo "Downloading Go module dependencies..."
                go mod download
                go mod tidy
            '''
        }

        stage('Security Scan') {
            def vulnResult = sh(
                script: '''
                    echo "Running vulnerability scan..."
                    go run golang.org/x/vuln/cmd/govulncheck@latest ./...
                ''',
                returnStatus: true
            )
            if (vulnResult != 0) {
                error 'Vulnerability scan found issues! Please review and fix before proceeding.'
            }
            echo 'Vulnerability scan passed successfully.'
        }

        stage('Test') {
            def testResult = sh(
                script: '''
                    echo "Running tests..."
                    go test -v  -coverprofile=coverage.out -coverpkg=./... ./test/...
                ''',
                returnStatus: true
            )
            sh 'ls -la'
            sh 'ls -la coverage.out || echo "No coverage report generated"'

            sh '''
                go tool cover -html=coverage.out -o coverage.html
                echo "Coverage report generated: coverage.html" 
            '''

            archiveArtifacts artifacts: 'coverage.html', allowEmptyArchive: true

            if (testResult != 0) {
                error 'Tests failed! Check the coverage report for details.'
            }
            echo 'Tests completed successfully.'
        }

        stage('Lint') {
            sh '''
                echo "Running linter..."
                golangci-lint run ./...
            '''
        }

        stage('Post declarative action') {
            sh '''
                echo "Cleaning up temporary files..."
                rm -f app.toml .env
                rm -rf config-repo
                echo "Cleanup completed"
            '''
        }
    }
}
