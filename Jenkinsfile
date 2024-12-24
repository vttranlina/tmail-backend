pipeline {
    agent any
    tools {
        jdk 'jdk_11_latest'
    }
    stages {
        stage('Build') {
            steps {
                sh 'mvn -version'
            }
        }
    }
}
