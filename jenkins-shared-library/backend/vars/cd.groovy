import hudson.*;

def call(Map params) {
    node('Agent') {
        stage('Set up tools') {
            def goHome = tool name: 'GoLatest', type: 'go'
            def dockerHome = tool name: 'Default', type: 'dockerTool'
            env.PATH = "${goHome}/bin:${dockerHome}/bin:${env.PATH}"
            
            script {
                def dockerBinPath = "${dockerHome}/bin"
                
                sh """
                    echo "Installing Docker Compose..."
                    if ! command -v docker-compose >/dev/null 2>&1; then
                        echo "Docker Compose not found, installing..."
                        curl -L "https://github.com/docker/compose/releases/download/1.29.2/docker-compose-\$(uname -s)-\$(uname -m)" -o ${dockerBinPath}/docker-compose
                        chmod +x ${dockerBinPath}/docker-compose
                        echo "Docker Compose installed to ${dockerBinPath}/docker-compose"
                    else
                        echo "Docker Compose already available"
                    fi
                """
            }
            
            env.PATH = "${env.PATH}:${dockerHome}/bin"
            
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