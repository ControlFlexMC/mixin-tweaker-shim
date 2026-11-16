#!/usr/bin/env bash
# Build the tweaker shim jar and optionally deploy it to a Minecraft instance.
#
# Usage:
#   ./tools/build-shim.sh
#   ./tools/build-shim.sh --release
#   ./tools/build-shim.sh --clean
#
# Environment:
#   MC_1_12_2_FORGE_PATH  instance game dir (…/minecraft); unset -> skip deploy (WARNING)
#   RELEASE_DEPLOY_PATH   extra copy dest on --release; unset -> WARNING
#   JAVA_HOME             build JVM; must be 8..19 (see below); auto-detected when unset
#
# WHY JAVA_HOME IS PINNED. The build uses `options.release = 8`, which only exists on
# javac 9+, so JDK 8 cannot build it -- but the system Gradle here is 8.1.1, which cannot
# run on a JDK newer than 19 ("Unsupported class file major version"). JDK 17 satisfies
# both. The script finds one rather than making every caller remember.
#
# The jar ships exactly three classes; `verifyShimJar` (part of `build`) fails otherwise,
# and this script re-states the count afterwards so a green build is not mistaken for a
# correct one.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

RELEASE=false
CLEAN=false
EXTRA_ARGS=()

for arg in "$@"; do
    case "$arg" in
        --release) RELEASE=true ;;
        --clean)   CLEAN=true ;;
        *)         EXTRA_ARGS+=("$arg") ;;
    esac
done

java_major() {
    "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1
}

pick_java_home() {
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
        local major
        major=$(java_major "${JAVA_HOME}/bin/java")
        if [[ -n "${major}" && "${major}" -ge 8 && "${major}" -le 19 ]]; then
            echo "${JAVA_HOME}"
            return 0
        fi
        echo "  note: JAVA_HOME=${JAVA_HOME} is JDK ${major}, outside 8..19; looking for another" >&2
    fi
    local candidate
    for candidate in /Library/Java/JavaVirtualMachines/*/Contents/Home \
                     /usr/lib/jvm/*; do
        [[ -x "${candidate}/bin/java" ]] || continue
        local major
        major=$(java_major "${candidate}/bin/java")
        if [[ -n "${major}" && "${major}" -ge 11 && "${major}" -le 19 ]]; then
            echo "${candidate}"
            return 0
        fi
    done
    return 1
}

if ! JAVA_HOME_PICKED="$(pick_java_home)"; then
    echo "No JDK 11..19 found. `options.release = 8` needs javac 9+, and Gradle 8.1.1 needs <= 19."
    echo "Set JAVA_HOME to such a JDK and retry."
    exit 1
fi
export JAVA_HOME="${JAVA_HOME_PICKED}"
echo "==> build JVM: ${JAVA_HOME} (JDK $(java_major "${JAVA_HOME}/bin/java"))"

cd "${PROJECT_DIR}"

if $CLEAN; then
    echo "==> Cleaning first..."
    gradle clean
fi

shim_version=$(sed -n "s/^version = '\(.*\)'/\1/p" build.gradle | head -1)
if [[ -z "${shim_version}" ]]; then
    echo "Could not read the version from build.gradle"
    exit 1
fi

if $RELEASE; then build_type="Release"; else build_type="Debug"; fi
echo "==> ${build_type} shim build: version ${shim_version}"

gradle_cmd=(gradle build)
if [[ ${#EXTRA_ARGS[@]} -gt 0 ]]; then gradle_cmd+=("${EXTRA_ARGS[@]}"); fi
echo "==> Running: ${gradle_cmd[*]}"
"${gradle_cmd[@]}"

NEW_JAR="${PROJECT_DIR}/build/libs/mixin-tweaker-shim-${shim_version}.jar"
if [[ ! -f "${NEW_JAR}" ]]; then
    echo "JAR not found: ${NEW_JAR}"
    exit 1
fi

class_count=$(unzip -l "${NEW_JAR}" | grep -c '\.class$' || true)
if [[ "${class_count}" -ne 3 ]]; then
    echo "Class count is ${class_count}, expected 3 -- verifyShimJar should have stopped this."
    exit 1
fi

echo ""
echo "Build complete: $(basename "${NEW_JAR}") (${class_count} classes)"

MC_INSTANCE="${MC_1_12_2_FORGE_PATH:-}"
if [[ -n "${MC_INSTANCE}" ]]; then
    MC_INSTANCE="${MC_INSTANCE%/}"
    # Accept either the game dir or the instance root; the launcher convention here is
    # <instance>/minecraft, and the main repo's deploy script normalises the same way.
    if [[ ! -d "${MC_INSTANCE}/mods" && -d "${MC_INSTANCE}/minecraft" ]]; then
        MC_INSTANCE="${MC_INSTANCE}/minecraft"
    fi
fi
MODS_DIR="${MC_INSTANCE:+${MC_INSTANCE}/mods}"

if [[ -z "${MC_INSTANCE}" ]]; then
    echo "MC_1_12_2_FORGE_PATH is not set; skipping instance deploy"
    echo "   artifact: ${NEW_JAR}"
else
    LOGS_DIR="${MC_INSTANCE}/logs"
    if [[ -d "${LOGS_DIR}" ]]; then
        rm -rf "${LOGS_DIR:?}"/*
        echo "  cleared logs: ${LOGS_DIR}"
    fi

    CRASH_DIR="${MC_INSTANCE}/crash-reports"
    if [[ -d "${CRASH_DIR}" ]]; then
        rm -rf "${CRASH_DIR:?}"/*
        echo "  cleared crash-reports: ${CRASH_DIR}"
    fi

    mkdir -p "${MODS_DIR}"
    if compgen -G "${MODS_DIR}/mixin-tweaker-shim-*.jar" > /dev/null; then
        rm -f "${MODS_DIR}"/mixin-tweaker-shim-*.jar
        echo "  removed old shim jars"
    fi
    cp "${NEW_JAR}" "${MODS_DIR}/"
    echo "  deployed: ${MODS_DIR}/$(basename "${NEW_JAR}")"
fi

if $RELEASE; then
    if [[ -n "${RELEASE_DEPLOY_PATH:-}" ]]; then
        mkdir -p "${RELEASE_DEPLOY_PATH}"
        cp "${NEW_JAR}" "${RELEASE_DEPLOY_PATH}/"
        echo "  release copy: ${RELEASE_DEPLOY_PATH}/$(basename "${NEW_JAR}")"
    else
        echo "RELEASE_DEPLOY_PATH is not set; skipping release copy"
    fi
fi
