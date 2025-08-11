def call(Map m){
    switch(m.PipelineKind) {
        case "pr":
            if(env.CHANGE_TARGET == 'develop' || env.CHANGE_TARGET == 'main') {
                sh 'Entering PR mode'
                ci.call()
            }
            break
        case "branch":
            if(env.BRANCH_NAME == 'develop' || env.BRANCH_NAME == 'main') {
                cd.call()
            }
            break
        default:
            error "Unsupported PipelineKind: ${m.PipelineKind}"
            break
    }

}
