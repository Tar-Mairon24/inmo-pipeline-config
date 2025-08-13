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
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: 'properties-repo']
                ]
            ])

                echo "Configuration repository checked out."
                sh 'ls -la'
                echo "Configuration files from properties-repo/:"
                sh 'ls -la properties-repo/'
        }

        stage('Parsing configuration') {
            script {
                def propertiesRepo = 'properties-repo'
                def configDir = "properties-repo/backend/${env.BRANCH_NAME}"
                def success = utils.toml2env(propertiesRepo, configDir)
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
                    string(credentialsId: 'dev_db_host', variable: 'DEV_DB_HOST'),
                    string(credentialsId: 'dev_db_password', variable: 'DEV_DB_PASSWORD')    
                ]){
                    sh '''
                        if [ ! -f .env ]; then
                            echo "ERROR: .env not found!"
                            exit 1
                        fi
                        if grep -q "dev_db_host" .env; then
                            sed -i "s/dev_db_host/${DEV_DB_HOST}/g" .env
                        else
                            echo "Warning: dev_db_host placeholder not found"
                        fi
                        
                        if grep -q "dev_db_pasword" .env; then
                            sed -i "s/dev_db_pasword/${DEV_DB_PASSWORD}/g" .env
                        else
                            echo "Warning: dev_db_pasword placeholder not found"
                        fi
                    '''
                }
                
            }
        }

        stage('Prepare Docker Compose') {
            checkout([
                $class: 'GitSCM',
                branches: [[name: 'main']],
                userRemoteConfigs: [[
                    url: 'https://github.com/Tar-Mairon24/inmo-pipeline-config.git',
                    credentialsId: 'Github_Token'
                ]],
                extensions: [
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: 'config-repo']
                ]
            ])

            echo "Docker Compose repository checked out."
            sh 'ls -la config-repo'
            sh 'ls -la config-repo/composeYamls/archetypes/'
            sh 'ls -la config-repo/composeYamls/archetypes/backend/'

        }

        stage('Deploy develop') {
            script {
                echo "Running Docker Compose with the following environment variables:"
                sh 'cat .env'
                
                sh '''
                    ls -la config-repo/composeYamls/archetypes/backend/compose.yml || echo "No compose.yml found in config-repo/composeYamls/archetypes/backend/"
                    docker-compose -f config-repo/composeYamls/archetypes/backend/compose.yml up -d
                    echo "Docker Compose started successfully."
                '''
            }
        }

        stage('Health Check') {
            script {
                echo "Running health check..."
                
                def healthCheckUrl = "http://localhost:3000/api/v1/health"
                
                sh """
                    sleep 10
                    if ! wget --no-verbose --tries=5 --spider "${healthCheckUrl}"; then
                        echo "Health check failed for ${healthCheckUrl}"
                        error "Service is not healthy"
                    else
                        echo "Health check passed for ${healthCheckUrl}"
                    fi
                """
            }
        }

        stage('Post actions') {
            script {
                echo "Performing post actions..."
                
                sh 'rm .env || echo "No .env file to remove"'
                sh 'rm -rf config-repo* || echo "No config-repo directory to remove"'
                sh 'rm -rf properties-repo* || echo "No properties-repo directory to remove"'
                
                echo "Post actions completed successfully."
            }
        }
    }
}