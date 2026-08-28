#!/usr/bin/env bash
#
# Download and unpack Spring source jars so you can read the implementation next to the notes.
#
#   ./tools/fetch-spring-sources.sh                       # the usual modules, pinned version
#   ./tools/fetch-spring-sources.sh spring-webmvc         # one extra module
#   SPRING_VERSION=6.2.7 ./tools/fetch-spring-sources.sh  # compare against another line
#
# Sources land in .spring-sources/<version>/<artifact>/ which is gitignored.
# Then just grep, e.g.:
#   grep -rn "class TaskSchedulerRouter" .spring-sources/7.0.9/spring-context/
#
set -euo pipefail

SPRING_VERSION="${SPRING_VERSION:-7.0.9}"
BOOT_VERSION="${BOOT_VERSION:-4.1.1}"
CENTRAL="https://repo1.maven.org/maven2"

DEFAULT_MODULES=(spring-core spring-beans spring-context spring-aop spring-tx)
MODULES=("${@:-}")
if [ -z "${MODULES[0]:-}" ]; then
  MODULES=("${DEFAULT_MODULES[@]}")
fi

for module in "${MODULES[@]}"; do
  case "$module" in
    spring-boot*)
      group_path="org/springframework/boot"
      version="$BOOT_VERSION"
      ;;
    *)
      group_path="org/springframework"
      version="$SPRING_VERSION"
      ;;
  esac

  dest=".spring-sources/${version}/${module}"
  if [ -d "$dest" ]; then
    echo "already have ${module} ${version}"
    continue
  fi

  jar="${module}-${version}-sources.jar"
  url="${CENTRAL}/${group_path}/${module}/${version}/${jar}"

  echo "fetching ${module} ${version}"
  mkdir -p "$dest"
  if ! curl -sfL --max-time 120 -o "${dest}/${jar}" "$url"; then
    echo "  could not download ${url}" >&2
    rmdir "$dest" 2>/dev/null || true
    continue
  fi

  unzip -oq "${dest}/${jar}" -d "$dest"
  rm -f "${dest}/${jar}"
  echo "  -> ${dest}"
done

echo
echo "Read along with:  grep -rn \"<what you are looking for>\" .spring-sources/"
