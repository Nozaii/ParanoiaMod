#!/bin/sh
# A/B d'implémentations dans une même session : sh tools/ab_run.sh ubo|sky|pet|none
cd "$(dirname "$0")/.." || exit 1
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Users/RTX~1/.aurora-tmp -Daurora.devworld=true -Daurora.devabimpl=$1"
timeout 900 ./gradlew runClient --no-daemon -q --args="--width 1600 --height 900 --quickPlaySingleplayer aurora_test" > "run/ab-$1.log" 2>&1
grep -A12 "A/B résultat" "run/ab-$1.log" || { grep -m5 "error\|Exception" "run/ab-$1.log"; exit 1; }
