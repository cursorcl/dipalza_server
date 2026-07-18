module.exports = {
  branches: ['main'],
  plugins: [
    '@semantic-release/commit-analyzer',
    '@semantic-release/release-notes-generator',
    '@semantic-release/changelog',
    ['@semantic-release/exec', {
      prepareCmd: 'cd dipalza && mvn versions:set -DnewVersion=${nextRelease.version} -DgenerateBackupPoms=false',
      publishCmd: 'cd dipalza && ./mvnw package -DskipTests -Dfrontend.skip=true'
    }],
    ['@semantic-release/git', {
      assets: ['dipalza/pom.xml', 'CHANGELOG.md'],
      message: 'chore(release): ${nextRelease.version} [skip ci]\n\n${nextRelease.notes}'
    }],
    ['@semantic-release/github', {
      assets: [{ path: 'dipalza/target/dipalza-*.jar' }]
    }]
  ]
};
