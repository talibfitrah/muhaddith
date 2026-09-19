#!/bin/bash
set -e
cd /home/claude/ahkam
T=/home/claude/misbar-android/tools/ahkam
python3 $T/extract_corpus_rulings.py --src /home/claude/misbar-data/clusters.db --work work --out work/text_rulings.jsonl > work/extract.log 2>&1
python3 $T/bulugh.py --json bulugh_almaram.json --work work --out work/bulugh_rulings.jsonl > work/bulugh.log 2>&1
python3 $T/match_api.py --work work --api . --src /home/claude/misbar-data/clusters.db --out work/api_rulings.jsonl > work/api.log 2>&1
python3 $T/albani.py --dir albani --work work --out work/albani.sqlite --rulings work/albani_rulings.jsonl > work/albani.log 2>&1
python3 $T/build_ahkam.py --work work --corpus /home/claude/misbar-data/misbar_corpus_full.db --out /home/claude/misbar-data/misbar_ahkam.db > work/build.log 2>&1
echo DONE > work/stage4.done
