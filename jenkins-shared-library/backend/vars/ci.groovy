import hudson.*;

def call(map params) {
    pipeline {
        agent {
            node {
                label 'Agent'
            }
        }
        environment {
            ENV = readProperties file: '.env-develop', encoding: 'UTF-8'
        }

        tools {
            go 'GoLatest'
            docker 'default'
        }

        stages {
            stage("Parameters"){
                steps{
                    script {
                        sh "whoami"

                        def pipelineKind = utils.getPipelineKind();

                        if(pipelineKind == "pr") {
                            env.BRANCH_NAME_TARGET = env.CHANGE_TARGET
                            env.BRANCH_NAME_ORIGIN = env.CHANGE_BRANCH
                        }else if(pipelineKind == "branch") {
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
                }
            }

            stage("Checkout") {
                steps {
                    checkout scm
                }
            }

            stage("Security Scan") {
                steps {
                    script {
                        def vulnResult = sh(
                            script: '''
                                echo "Running vulnerability scan..."
                                go run golang.org/x/vuln/cmd/govulncheck@latest ./...
                            ''',
                            returnStatus: true
                        )
                        
                        if (vulnResult != 0) {
                            error "Vulnerability scan found issues! Please review and fix before proceeding."
                        }
                        
                        echo "Vulnerability scan passed successfully."
                    }
                }
            }

            stage("Test") {
                steps {
                    script {
                        def testResult = sh(
                            script: '''
                                echo "Running tests..."
                                go test -v -race -coverprofile=coverage.out -coverpkg=./... ./test/... || true
                            ''',
                            returnStatus: true
                        )
                        
                        // Always generate coverage report
                        sh '''
                            go tool cover -html=coverage.out -o coverage.html
                            echo "Coverage report generated: coverage.html" 
                        '''
                        
                        // Then fail if tests failed
                        if (testResult != 0) {
                            error "Tests failed! Check the coverage report for details."
                        }
                        
                        echo "Tests completed successfully."
                    }
                }
            }
        }
    }
}