#!/bin/bash
set -e
cd /home/claude/ahkam
T=/home/claude/misbar-android/tools/ahkam
python3 $T/index_corpus.py --src /home/claude/misbar-data/clusters.db --out work > work/index.log 2>&1
python3 $T/extract_corpus_rulings.py --src /home/claude/misbar-data/clusters.db --work work --out work/text_rulings.jsonl > work/extract.log 2>&1
python3 $T/bulugh.py --json bulugh_almaram.json --work work --out work/bulugh_rulings.jsonl > work/bulugh.log 2>&1
python3 $T/match_api.py --work work --api . --src /home/claude/misbar-data/clusters.db --out work/api_rulings.jsonl > work/api.log 2>&1
python3 $T/import_texts.py --data openiti/data --work work --out work/texts.sqlite > work/import.log 2>&1
echo DONE > work/stage1.done
