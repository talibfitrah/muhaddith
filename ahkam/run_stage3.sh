#!/bin/bash
set -e
cd /home/claude/ahkam
T=/home/claude/misbar-android/tools/ahkam
python3 $T/extract_corpus_rulings.py --src /home/claude/misbar-data/clusters.db --work work --out work/text_rulings.jsonl > work/extract.log 2>&1
python3 $T/bulugh.py --json bulugh_almaram.json --work work --out work/bulugh_rulings.jsonl > work/bulugh.log 2>&1
python3 $T/import_texts.py --data openiti/data --work work --out work/texts.sqlite > work/import.log 2>&1
python3 $T/build_ahkam.py --work work --corpus /home/claude/misbar-data/misbar_corpus_full.db --out /home/claude/misbar-data/misbar_ahkam.db > work/build.log 2>&1
cd /home/claude/misbar-data && python3 $T/pack_ahkam.py --db misbar_ahkam.db --out parts --manifest manifest.json --known /home/claude/misbar-android/app/src/main/java/com/misbar/hadith/data/KnownPacks.kt > /home/claude/ahkam/work/pack.log 2>&1
echo DONE > /home/claude/ahkam/work/stage3.done
