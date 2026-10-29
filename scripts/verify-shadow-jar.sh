#!/usr/bin/env bash
set -euo pipefail

shopt -s nullglob
candidates=(build/libs/ArcOrigin-*.jar)
production_jars=()
for candidate in "${candidates[@]}"; do
  [[ "$candidate" == *-plain.jar ]] || production_jars+=("$candidate")
done

[[ ${#production_jars[@]} -eq 1 ]] || {
  printf 'Expected exactly one production ArcOrigin JAR, found %s\n' "${#production_jars[@]}" >&2
  exit 1
}

jar_path="${production_jars[0]}"
[[ -s "$jar_path" ]] || { printf 'Production JAR is empty: %s\n' "$jar_path" >&2; exit 1; }

entries="$(mktemp)"
descriptor="$(mktemp)"
trap 'rm -f "$entries" "$descriptor"' EXIT
jar tf "$jar_path" > "$entries"
unzip -p "$jar_path" plugin.yml > "$descriptor"
grep -Fxq 'name: ArcOrigin' "$descriptor"
grep -Fxq 'main: ru.arc.origin.ArcOriginPlugin' "$descriptor"
grep -Fxq 'depend: [ARC]' "$descriptor"
grep -Fxq 'kotlin/collections/CollectionsKt.class' "$entries"

if grep -Eq '^(ru/arc/origin/api/|ru/arc/mounts/api/|ru/arc/paper/api/|ru/arc/paper/display/PaperDisplayPassengerServiceProvider[^/]*\.class$|ru/arc/paper/scene/PaperScene(Observation|EntityInfo)ServiceProvider[^/]*\.class$|ru/arc/paper/sql/(PaperSqlServiceProvider|SharedPaperSqlService)[^/]*\.class$|ru/arc/paper/redis/(PaperRedisServiceProvider|SharedPaperRedisService)[^/]*\.class$|ru/arc/paper/metrics/PaperArcMetricsProvider[^/]*\.class$|ru/arc/paper/runtime/PaperNativeCleanupServiceProvider[^/]*\.class$|org/bukkit/|io/papermc/)' "$entries"; then
  printf 'Production JAR embeds an ARC-owned service/API or server-provided Paper class.\n' >&2
  exit 1
fi

printf 'Verified production JAR: %s\n' "$jar_path"
