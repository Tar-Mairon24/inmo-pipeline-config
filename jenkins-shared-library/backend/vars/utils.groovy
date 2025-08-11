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

def dockerCleanup(imageName) {
    sh """
        IMAGE_COUNT=\$(docker images ${imageName} --format "{{.Tag}}" | grep -v "latest" | wc -l)
        echo "Current image count (excluding latest): \$IMAGE_COUNT"
        
        if [ "\$IMAGE_COUNT" -gt 5 ]; then
            echo "Removing old images to keep only 5 most recent..."
            
            OLD_IMAGES=\$(docker images ${imageName} --format "{{.ID}} {{.CreatedAt}} {{.Tag}}" | \\
                        grep -v "latest" | \\
                        sort -k2 -r | \\
                        tail -n +6 | \\
                        awk '{print \$1}')
            
            if [ -n "\$OLD_IMAGES" ]; then
                echo "Removing images: \$OLD_IMAGES"
                echo "\$OLD_IMAGES" | xargs docker rmi -f || echo "Some images could not be removed (might be in use)"
            else
                echo "No old images to remove"
            fi
        else
            echo "Only \$IMAGE_COUNT images found, no cleanup needed"
        fi
        
        echo "Final image list:"
        docker images ${imageName}
        
        echo "Removing dangling images..."
        docker image prune -f || echo "No dangling images to remove"
        
        echo "Docker cleanup completed."
    """
}