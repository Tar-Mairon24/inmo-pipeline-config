import hudson.*;

def call(Map params) {
    node('Agent') {
        environment {
            env.ENVIRONMENT = env.ENVIRONMENT ?: 'develop'
            env.BRANCH_NAME = env.BRANCH_NAME ?: 'develop'
        }

        stage('Set up tools') {
            def dockerHome = tool name: 'Default', type: 'dockerTool'
            
            env.PATH = "${dockerHome}/bin:${env.PATH}"
            
            sh '''
                echo "=== Installing Docker Compose ==="
                if ! command -v docker-compose >/dev/null 2>&1; then
                    echo "Docker Compose not found, installing..."
                    COMPOSE_VERSION="2.39.0"
                    
                    # Create a local bin directory if it doesn't exist
                    mkdir -p $HOME/bin
                    
                    # Download Docker Compose
                    curl -L "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-$(uname -s)-$(uname -m)" -o $HOME/bin/docker-compose
                    
                    # Make it executable
                    chmod +x $HOME/bin/docker-compose
                    
                    echo "Docker Compose installed to $HOME/bin/docker-compose"
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
                def configDir = "config-repo/backend/${env.ENVIRONMENT}"
                def success = utils.toml2env(configDir)
                if (!success) {
                    error "Failed to parse configuration from ${configDir}"
                }
                sh 'ls -la'
            }
        }
    }
}