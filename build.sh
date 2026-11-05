#!/bin/sh
# Builds dist/md-edit.jar (a runnable jar, Main-Class com.mdedit.Main).
#
# Usage:  ./build.sh                         build the jar (version from the VERSION file)
#         ./build.sh release [patch|minor|major]
#                                            increment the version (default: patch), then build
#         ./build.sh clean                   remove build/ and dist/
#
# Uses $JAVA_HOME/bin/javac and $JAVA_HOME/bin/jar when JAVA_HOME is set,
# otherwise javac and jar from the PATH. Needs a JDK 11 or newer.
set -eu

cd "$(dirname "$0")"

if [ "${1:-}" = "clean" ]; then
    rm -rf build dist
    echo "Cleaned build/ and dist/."
    exit 0
fi

# ---- version -------------------------------------------------------------
if [ ! -f VERSION ]; then
    echo "1.0.0" > VERSION
fi
VERSION=$(tr -d ' \t\r\n' < VERSION)
if ! echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "ERROR: VERSION file must contain MAJOR.MINOR.PATCH (found '$VERSION')." >&2
    exit 1
fi

if [ "${1:-}" = "release" ]; then
    PART=${2:-patch}
    MAJOR=${VERSION%%.*}; REST=${VERSION#*.}; MINOR=${REST%%.*}; PATCH=${REST#*.}
    case "$PART" in
        major) MAJOR=$((MAJOR + 1)); MINOR=0; PATCH=0 ;;
        minor) MINOR=$((MINOR + 1)); PATCH=0 ;;
        patch) PATCH=$((PATCH + 1)) ;;
        *) echo "ERROR: release part must be patch, minor or major (got '$PART')." >&2; exit 1 ;;
    esac
    NEW="$MAJOR.$MINOR.$PATCH"
    echo "$NEW" > VERSION
    # Keep the Maven project version (the first <version> in pom.xml) in sync.
    if [ -f pom.xml ]; then
        awk -v v="$NEW" '!done && /<version>[^<]*<\/version>/ { sub(/<version>[^<]*<\/version>/, "<version>" v "</version>"); done=1 } { print }' pom.xml > pom.xml.tmp && mv pom.xml.tmp pom.xml
    fi
    echo "Version $VERSION -> $NEW"
    VERSION=$NEW
elif [ -n "${1:-}" ]; then
    echo "ERROR: unknown command '$1'. Use: ./build.sh [release [patch|minor|major] | clean]" >&2
    exit 1
fi

find_tool() {
    # $1 = tool name (javac / jar)
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/$1" ]; then
        echo "$JAVA_HOME/bin/$1"
    elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/$1.exe" ]; then
        echo "$JAVA_HOME/bin/$1.exe"
    elif command -v "$1" >/dev/null 2>&1; then
        command -v "$1"
    else
        return 1
    fi
}

if ! JAVAC=$(find_tool javac); then
    echo "ERROR: javac not found." >&2
    echo "Install a JDK (version 11 or newer) and either put its bin directory on" >&2
    echo "your PATH or set JAVA_HOME, e.g.  JAVA_HOME=/path/to/jdk ./build.sh" >&2
    exit 1
fi
if ! JAR=$(find_tool jar); then
    echo "ERROR: the 'jar' tool was not found next to javac ($JAVAC)." >&2
    echo "Set JAVA_HOME to a full JDK installation." >&2
    exit 1
fi

echo "Using $JAVAC"

CLASSES=build/classes
rm -rf "$CLASSES"
mkdir -p "$CLASSES" dist

# List the sources in an argument file (quoted, so paths with spaces work).
find src/main/java -name '*.java' | sed 's/.*/"&"/' > build/sources.txt
if [ ! -s build/sources.txt ]; then
    echo "ERROR: no Java sources found under src/main/java" >&2
    exit 1
fi

echo "Compiling $(wc -l < build/sources.txt | tr -d ' ') source files..."
if ! "$JAVAC" --release 11 -encoding UTF-8 -nowarn -d "$CLASSES" @build/sources.txt; then
    echo "ERROR: compilation failed (javac must be from JDK 11 or newer)." >&2
    exit 1
fi

if [ -d src/main/resources ]; then
    cp -R src/main/resources/. "$CLASSES"/
fi

printf 'version=%s\n' "$VERSION" > "$CLASSES/com/mdedit/version.properties"
printf 'Main-Class: com.mdedit.Main\nImplementation-Title: MD Edit\nImplementation-Version: %s\n' "$VERSION" > build/manifest.txt

rm -f dist/md-edit.jar
"$JAR" cfm dist/md-edit.jar build/manifest.txt -C "$CLASSES" .

echo "Built dist/md-edit.jar (version $VERSION)"
echo "Run it with:  java -jar dist/md-edit.jar [file.md]"
