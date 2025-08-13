def getPipelineKind() {
    if(env.CHANGE_TARGET != null){
        return "pr"
    } else {
        return "branch"
    }
}

def getEnviromentName(branchName) {
    print("Branch name: ${branchName}")
    if("develop".equals(branchName)) {
        return "develop"
    } else {
        return "production"
    }
}

def toml2env(propertiesRepo, configDir) {
    def configFile = "${configDir}/app.toml"

    try {
        sh """
        echo "Looking for configuration: ${configFile}"
        if [ ! -f "${configFile}" ]; then
            echo "Configuration file not found: ${configFile}"
            echo "Available backend configurations:"
            find ${configFile} -name "*.toml" || echo "No TOML files found"
            exit 1
        fi
        echo "Found configuration file"
        echo "Content preview:"
        head -20 "${configFile}"

        echo "Converting TOML to .env format..."
        cp "${configFile}" ${propertiesRepo}/tools/app.toml
        if [ ! -f "${propertiesRepo}/tools/toml2env.go" ]; then
            echo "Converter not found: ${propertiesRepo}/tools/toml2env.go"
            echo "Available files in tools directory:"
            ls -la ${propertiesRepo}/tools/ || echo "Tools directory not found"
            exit 1
        fi
        ls -la
        ls -la ${propertiesRepo}/tools 
        echo "Running conversion..."
        cd ${propertiesRepo}/tools/
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

        echo "Removing ${propertiesRepo} to avoid Go modules issues..."
        rm -rf ${propertiesRepo} || echo "No ${propertiesRepo} directory to remove"
        rm -rf ${propertiesRepo}@* || echo "No ${propertiesRepo}@temp directory to remove"
        ls -la
    """
        return true
    } catch (Exception e) {
        echo "Error during TOML to ENV conversion: ${e.getMessage()}"
        error "Failed to convert TOML to ENV"
        return false
    }
}

def dockerCleanup(imageName, dockerHubRepo) {
    sh """
        echo "=== Removing all local images after successful push ==="
        
        echo "Removing local ${imageName} images..."
        docker images ${imageName} --format "{{.Repository}}:{{.Tag}}" | xargs -r docker rmi -f || echo "Some ${imageName} images could not be removed"
        
        echo "Removing locally tagged Docker Hub images..."
        docker images ${dockerHubRepo} --format "{{.Repository}}:{{.Tag}}" | xargs -r docker rmi -f || echo "Some ${dockerHubRepo} images could not be removed"
        
        echo "Removing dangling images..."
        docker image prune -f || echo "No dangling images to remove"
        
        echo "=== Remaining images for ${imageName} ==="
        docker images | grep -E "(${imageName}|${dockerHubRepo})" || echo "No local images remaining (as expected)"
        
        echo "Local cleanup completed - all images removed after successful push"
    """
}