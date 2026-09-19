#!/bin/bash
set -e
cd /home/claude/ahkam
T=/home/claude/misbar-android/tools/ahkam
python3 $T/extract_corpus_rulings.py --src /home/claude/misbar-data/clusters.db --work work --out work/text_rulings.jsonl > work/extract.log 2>&1
python3 $T/build_ahkam.py --work work --corpus /home/claude/misbar-data/misbar_corpus_full.db --out /home/claude/misbar-data/misbar_ahkam.db > work/build.log 2>&1
echo DONE > work/stage2.done
