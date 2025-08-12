import hudson.*;

def call(Map params) {
    node('Agent') {
        environment {
            env.ENVIRONMENT = env.ENVIRONMENT ?: 'develop'
            env.BRANCH_NAME = env.BRANCH_NAME ?: 'develop'
        }

        stage('Set up tools') {
            def goHome = tool name: 'GoLatest', type: 'go'
            def dockerHome = tool name: 'Default', type: 'dockerTool'
            
            env.PATH = "${goHome}/bin:${dockerHome}/bin:${env.PATH}"
            
            sh '''
                echo "Installing Docker Compose..."
                if ! command -v docker-compose >/dev/null 2>&1; then
                    echo "Docker Compose not found, installing..."
                    COMPOSE_VERSION="1.29.2"
                    curl -L "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-$(uname -s)-$(uname -m)" -o $(go env GOPATH)/bin/docker-compose
                    chmod +x $(go env GOPATH)/bin/docker-compose
                    echo "Docker Compose installed to $(go env GOPATH)/bin/docker-compose"
                else
                    echo "Docker Compose already available"
                fi
            '''
            
            env.PATH = "$HOME/bin:${env.PATH}"
            
            echo "=== Tool Setup Complete ==="

            sh '''
                echo "=== Tool Verification ==="
                echo "Docker version: $(docker --version)"
                echo "Docker Compose version: $(docker-compose --version || echo 'Docker Compose not available')"
                echo "PATH: $PATH"
                echo "=== Verification Complete ==="
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
                def configDir = "config-repo/backend/${env.BRANCH_NAME}"
                def success = utils.toml2env(configDir)
                if (!success) {
                    error "Failed to parse configuration from ${configDir}"
                }
                sh 'ls -la'
            }
        }
    }
}