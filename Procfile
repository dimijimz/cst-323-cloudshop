# Heroku process types.
#
# The jar name comes from <finalName>cloudshop</finalName> in pom.xml, so it is
# stable across version bumps - no target/*.jar glob needed. Elastic Beanstalk is
# unaffected by this file: only the jar is uploaded there, and the Java SE platform
# runs a lone jar with `java -jar`, so the same build output serves both platforms.
#
# The app also needs its config vars, DISABLE_SPRING_DATASOURCE_URL=true among them:
# without it the Java buildpack's own datasource URL overrides the DB_* variables.
# docs/deploy/heroku-config.sh sets them all and explains why.
#
# $PORT is assigned by the dyno at boot. server.port in application.properties already
# reads ${PORT:8080}; passing -Dserver.port here as well is the conventional Heroku
# form and keeps the binding explicit at the process definition.

web: java -Dserver.port=$PORT -jar target/cloudshop.jar
