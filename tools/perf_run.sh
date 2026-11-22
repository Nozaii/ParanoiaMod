#!/bin/sh
# Mesure reproductible : lance le client de dev sur une scène fixe puis analyse le bench.
# Usage : sh tools/perf_run.sh off|user <étiquette>
# Scènes (DevWorld, -Daurora.devprof) : « off » = tous les modules et cosmétiques coupés (coût fixe du mod),
# « user » = réglages par défaut + cosmétiques, ciel, sol miroir, Hand Aura. 24 animaux immobiles autour.
cd "$(dirname "$0")/.." || exit 1
SCENE=${1:-user}
LABEL=${2:-run}
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Users/RTX~1/.aurora-tmp -Daurora.devworld=true -Daurora.devprof=$SCENE"
before=$(ls -t run/paranoia-bench/*.jfr 2>/dev/null | head -1)
timeout 900 ./gradlew runClient --no-daemon -q --args="--width 1600 --height 900 --quickPlaySingleplayer aurora_test" > "run/perf-$SCENE-$LABEL.log" 2>&1
after=$(ls -t run/paranoia-bench/*.jfr 2>/dev/null | head -1)
if [ "$after" = "$before" ] || [ -z "$after" ]; then echo "pas de nouveau bench (voir run/perf-$SCENE-$LABEL.log)"; exit 1; fi
cp "$after" "run/paranoia-bench/$SCENE-$LABEL.jfr"
cp "${after%.jfr}.txt" "run/paranoia-bench/$SCENE-$LABEL.txt"
python tools/perf_report.py "run/paranoia-bench/$SCENE-$LABEL.jfr" 25 | tee "run/paranoia-bench/$SCENE-$LABEL.report.txt"
