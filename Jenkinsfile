pipeline {
    agent any
    environment {
        MAVEN_HOME = '/opt/apache-maven-3.9.9'
        PATH = "${MAVEN_HOME}/bin:${env.PATH}"
    }
    stages {
        stage('Build') {
            steps {
                sh 'mvn -version'
            }
        }
    }
}
