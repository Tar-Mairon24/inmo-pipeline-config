import hudson.*;

def call(Map params) {
    node('Agent') {
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

        stage('Parameters') {
            sh 'whoami'

            def pipelineKind = utils.getPipelineKind()

            } if (pipelineKind == 'branch') {
                case env.BRANCH_NAME {
                    case 'develop':
                        env.BRANCH_NAME_TARGET = 'develop'
                        env.BRANCH_NAME_ORIGIN = 'main'
                        break
                    case 'main':
                        env.BRANCH_NAME_TARGET = 'production'
                        env.BRANCH_NAME_ORIGIN = 'develop'
                        break
                    default:
                        error "Unsupported branch: ${env.BRANCH_NAME}"
                }
            } else {
                error "Unsupported PipelineKind: ${pipelineKind}"
            }

            env.ENVIRONMENT_NAME = utils.getEnviromentName(env.BRANCH_NAME_TARGET)

            echo "BRANCH_NAME_TARGET: $BRANCH_NAME_TARGET"
            echo "BRANCH_NAME_ORIGIN: $BRANCH_NAME_ORIGIN"
            echo "ENVIRONMENT_NAME: $ENVIRONMENT_NAME"
        }
    }
}