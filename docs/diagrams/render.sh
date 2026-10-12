#!/usr/bin/env bash
# Regenerates every design-report diagram in docs/diagrams as a PNG.
#
#   ./docs/diagrams/render.sh
#   ./docs/diagrams/render.sh --export docs/design/milestone4
#
# Needs only a JDK. PlantUML is fetched once from Maven Central by the Maven
# wrapper, into target/plantuml (gitignored), and every diagram that needs a
# layout engine uses PlantUML's built-in "smetana", so Graphviz is not required.
#
# Three diagrams are written by hand and rendered as they are:
#   sitemap.puml, er-diagram.puml, shopping-flowchart.puml
# The five UML class diagrams are generated from the compiled classes first, by
# tools/UmlClassDiagrams.java, so they always match the code:
#   uml-models.puml, uml-controllers.puml, uml-services.puml, uml-repositories.puml,
#   uml-logging.puml
#
# With --export and a directory (relative to the project root), the five UML class
# diagrams are also written there at the width a report page takes: about 1500
# pixels, or EXPORT_WIDTH if that is set. Each one is rendered again at the
# resolution that gives that width, not resized, so its text stays sharp.

set -euo pipefail
cd "$(dirname "$0")/../.."

EXPORT_DIR=""
if [ "${1:-}" = "--export" ]; then
    EXPORT_DIR="${2:?--export needs a directory, for example docs/design/milestone4}"
fi
EXPORT_WIDTH="${EXPORT_WIDTH:-1500}"

PLANTUML_VERSION=1.2026.8
PLANTUML_JAR="target/plantuml/plantuml-${PLANTUML_VERSION}.jar"

# The width of a PNG in pixels: a big-endian integer in bytes 17 to 20 of the file.
png_width() {
    od -An -tu1 -j16 -N4 "$1" | awk '{ print $1 * 16777216 + $2 * 65536 + $3 * 256 + $4 }'
}

# The limit is raised because the class diagrams are wider than PlantUML's 4096px default.
plantuml() {
    PLANTUML_LIMIT_SIZE=16384 java -Djava.awt.headless=true -jar "$PLANTUML_JAR" \
        -tpng -charset UTF-8 -failfast2 "$@"
}

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
plantuml docs/diagrams/*.puml

ls -1 docs/diagrams/*.png

if [ -n "$EXPORT_DIR" ]; then
    echo "Exporting the UML class diagrams to ${EXPORT_DIR}, about ${EXPORT_WIDTH}px wide..."
    rm -rf target/plantuml/export
    mkdir -p target/plantuml/export "$EXPORT_DIR"
    for source in docs/diagrams/uml-*.puml; do
        name="$(basename "$source" .puml)"
        # A diagram's width is proportional to its resolution, so the resolution that
        # gives the wanted width follows from the width of the PNG rendered just above.
        dpi="$(sed -n 's/^skinparam dpi \([0-9][0-9]*\)$/\1/p' "$source")"
        width="$(png_width "docs/diagrams/${name}.png")"
        sed "s/^skinparam dpi .*/skinparam dpi $(( (dpi * EXPORT_WIDTH + width / 2) / width ))/" \
            "$source" > "target/plantuml/export/${name}.puml"
    done
    plantuml -o "$(cd "$EXPORT_DIR" && pwd)" target/plantuml/export/*.puml

    for png in "$EXPORT_DIR"/uml-*.png; do
        echo "${png} $(png_width "$png")px wide"
    done
fi
