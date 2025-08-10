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