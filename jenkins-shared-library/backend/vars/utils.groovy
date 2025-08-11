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

def toml2env(configDir) {
    def configFile = "${configDir}/app.toml"

    try {
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

        echo "Converting TOML to .env format..."
        cp "${configFile}" config-repo/tools/app.toml
        if [ ! -f "config-repo/tools/toml2env.go" ]; then
            echo "Converter not found: config-repo/tools/toml2env.go"
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

        echo "Removing config-repo to avoid Go modules issues..."
        rm -rf config-repo || echo "No config-repo directory to remove"
        rm -rf config-repo@* || echo "No config-repo@temp directory to remove"
        ls -la
    """
        return true
    } catch (Exception e) {
        echo "Error during TOML to ENV conversion: ${e.getMessage()}"
        error "Failed to convert TOML to ENV"
        return false
    }
}

def dockerCleanup(imageName, maxImages) {
    sh """
        echo "Starting Docker cleanup for ${imageName}..."
        
        REPOS="${imageName} tarmairon24/${imageName}"
        
        for REPO in \$REPOS; do
            IMAGE_COUNT=\$(docker images \$REPO --format "{{.Tag}}" | grep -v "latest" | wc -l)
            echo "Current image count for \$REPO (excluding latest): \$IMAGE_COUNT"
            
            if [ "\$IMAGE_COUNT" -gt ${maxImages} ]; then
                OLD_IMAGES=\$(docker images \$REPO --format "{{.ID}} {{.CreatedAt}} {{.Tag}}" | \\
                            grep -v "latest" | \\
                            sort -k2 -r | \\
                            tail -n +\$((${maxImages} + 1)) | \\
                            awk '{print \$1}') 
                if [ -n "\$OLD_IMAGES" ]; then
                    echo "Removing images from \$REPO: \$OLD_IMAGES"
                    echo "\$OLD_IMAGES" | xargs docker rmi -f || echo "Some images could not be removed (might be in use)"
                else
                    echo "No old images to remove from \$REPO"
                fi
            else
                echo "Only \$IMAGE_COUNT images found for \$REPO, no cleanup needed"
            fi
        done
        docker images | grep -E "(inmo-backend|tarmairon24/inmo-backend)"
        docker image prune -f || echo "No dangling images to remove"
        
        echo "Docker cleanup completed."
    """
}