#!/bin/bash
# Прогон основных adb-команд по одному разу — быстрая проверка, что всё живо.
set -u
cd /home/nikita/projects/binaural-cycles-android || exit 1

run() {
    echo "--- $1"
    bash tools/dbgcmd.sh "$1" | tail -1
    sleep 1
}

run "next"
run "prev"
run "volume 0.45"
run "samplerate 44100"
run "buffer 5"
run "norm off"
run "tnorm on"
run "swap timer"
run "swapinterval 30"
run "swapfade off"
run "vtime on"
run "scrub 36000"
run "scale 10"
run "vrun off"
run "scale 1"
run "realtime"
run "vtime off"
echo "--- status"
bash tools/dbgcmd.sh status