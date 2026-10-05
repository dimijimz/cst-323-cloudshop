# Heroku process types.
#
# The jar name comes from <finalName>cloudshop</finalName> in pom.xml, so it is
# stable across version bumps - no target/*.jar glob needed. Azure is unaffected
# by this file: `az webapp deploy --type jar` renames the artifact to app.jar during
# upload, so the same build output serves both platforms.
#
# $PORT is assigned by the dyno at boot. server.port in application.properties already
# reads ${PORT:8080}; passing -Dserver.port here as well is the conventional Heroku
# form and keeps the binding explicit at the process definition.

web: java -Dserver.port=$PORT -jar target/cloudshop.jar
