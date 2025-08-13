import hudson.*;

def call(Map params) {
    node('Agent') {
        stage('Checkout SCM') {
            checkout scm

            echo "Checked out branch: ${env.BRANCH_NAME}"
            sh '''
                echo "Current branch: $(git rev-parse --abbrev-ref HEAD)"
                echo "Last commit: $(git log -1 --pretty=format:'%h - %s')"
                ls -la
            '''
        }

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

        stage('Replace Secrets') {
            script {
                echo "Replacing credential placeholders with actual values..."
                
                withCredentials([
                    string(credentialsId: 'dev_db_host', variable: 'dev_db_host'),
                    string(credentialsId: 'dev_db_pasword', variable: 'dev_db_password')
                ]) {
                    sh '''
                        cat .env
                        if [ ! -f .env ]; then
                            echo "ERROR: .env not found!"
                            exit 1
                        fi
                        if grep -q "dev_host" .env; then
                            sed -i "s/dev_host/${dev_db_host}/g" .env
                        else
                            echo "Warning: dev_db_host placeholder not found"
                        fi
                        
                        if grep -q "dev_pasword" .env; then
                            sed -i "s/dev_pasword/${dev_db_password}/g" .env
                        else
                            echo "Warning: dev_db_pasword placeholder not found"
                        fi

                        cat .env
                    '''
                }
            }
        }
    }
}