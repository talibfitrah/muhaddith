#!/bin/bash
# توسيع حزمة الشروح بكتب المكتبة الوقفية (المصنفات وكتب الآثار وكبار الشروح) ثم إعادة البناء والتقسيم
# يُشغَّل من بيئة تصل إلى huggingface.co وفيها clusters.db وفهرس work/ (بيئة البناء):
#   bash tools/ahkam/expand_shuruh.sh [--only مصنف]
set -e
T=$(cd "$(dirname "$0")" && pwd)
A=/home/claude/ahkam; D=/home/claude/muhaddith-data
python3 "$T/fetch_hf.py" --index "$A/hf_index.tsv" --out "$A/shuruh_ocr" ${1:+--only "$2"}
python3 "$T/shuruh.py" --data "$A/openiti/data" --ocr "$A/shuruh_ocr" --work "$A/work" --corpus "$D/muhaddith_corpus_full.db" --out "$D/muhaddith_shuruh.db" | tail -20
cd "$D" && python3 "$T/pack_db.py" --db muhaddith_shuruh.db --id shuruh --const SHURUH --name "شروح الحديث وأسباب وروده وآثار الصحابة" --out parts --manifest manifest.json --known "$T/../../app/src/main/java/org/murabbie/muhaddith/data/KnownPacks.kt" | grep -v "db.gz.0"
echo "ثم: انسخ parts/muhaddith_shuruh.db.gz.* وmanifest.json إلى مجلد النشر وشغّل tools/nas_publish.py --dir <المجلد> --notes '…'"
