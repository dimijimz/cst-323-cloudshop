#!/usr/bin/env bash
# Regenerates every design-report diagram in docs/diagrams as a PNG.
#
#   ./docs/diagrams/render.sh
#
# Needs only a JDK. PlantUML is fetched once from Maven Central by the Maven
# wrapper, into target/plantuml (gitignored), and every diagram that needs a
# layout engine uses PlantUML's built-in "smetana", so Graphviz is not required.
#
# Three diagrams are written by hand and rendered as they are:
#   sitemap.puml, er-diagram.puml, shopping-flowchart.puml
# The four UML class diagrams are generated from the compiled classes first, by
# tools/UmlClassDiagrams.java, so they always match the code:
#   uml-models.puml, uml-controllers.puml, uml-services.puml, uml-repositories.puml

set -euo pipefail
cd "$(dirname "$0")/../.."

PLANTUML_VERSION=1.2026.8
PLANTUML_JAR="target/plantuml/plantuml-${PLANTUML_VERSION}.jar"

mkdir -p target/plantuml

echo "Compiling and resolving the classpath..."
./mvnw -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/plantuml/classpath.txt

if [ ! -f "$PLANTUML_JAR" ]; then
    echo "Fetching PlantUML ${PLANTUML_VERSION} from Maven Central..."
    ./mvnw -q dependency:copy \
        -Dartifact="net.sourceforge.plantuml:plantuml:${PLANTUML_VERSION}" \
        -DoutputDirectory=target/plantuml
fi

echo "Generating the UML class diagram sources from target/classes..."
java docs/diagrams/tools/UmlClassDiagrams.java .

echo "Rendering PNGs..."
# The limit is raised because the class diagrams are wider than PlantUML's 4096px default.
PLANTUML_LIMIT_SIZE=16384 java -Djava.awt.headless=true -jar "$PLANTUML_JAR" \
    -tpng -charset UTF-8 -failfast2 docs/diagrams/*.puml

ls -1 docs/diagrams/*.png
