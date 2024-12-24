pipeline {
    agent any
    tools {
        jdk 'jdk_11'
    }
    stages {
        stage('Build') {
            steps {
                sh 'mvn -version'
            }
        }
    }
}
