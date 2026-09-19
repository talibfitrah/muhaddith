# -*- coding: utf-8 -*-
"""
شروح الحديث وأسباب وروده: يستورد كتب الشروح (OpenITI) والشروح المعاصرة (OCR)، يقسمها مقاطع، يربطها بالأحاديث المجمَّعة،
ويستخرج من كل مقطع «الاستنباطات»: أقوال العلماء المسنَدة («قال ابن عباس…»، «قال الشافعي…») وفوائد الشارح نفسه («وفي الحديث…»، «ويستفاد منه…»)،
مصنَّفةً آليًّا (عقدية/فقهية/اجتماعية وأخلاقية/تربوية/لغوية/حديثية/أخرى) ومرتَّبة بوفاة القائل، مع مصدر كل قول (الكتاب، الجزء، الصفحة).
كما يبني «سياق الحديث وقصته» من الروايات الثابتة نفسها في المتن، وكتب أسباب الورود.

  python3 shuruh.py --data openiti/data --ocr shuruh_ocr --work work --corpus muhaddith_corpus_full.db --out muhaddith_shuruh.db
"""
import argparse, glob, json, os, re, sqlite3, sys, time, zlib
from collections import defaultdict
sys.path.insert(0, os.path.dirname(__file__))
from openiti import parse, read_meta, clean_para
from matcher import CorpusIndex
from common import content_words, strip_diac, norm
from rulings import canon, BAD_NAMES, BAD_TOKENS
from import_texts import find_file

# (معرّف OpenITI، العنوان، المؤلف، الوفاة، النوع، الترتيب)
BOOKS = [
    ("0911Suyuti.LumacFiAsbabHadith", "اللمع في أسباب الحديث", "السيوطي", 911, "asbab", 1),
    ("1120IbnMuhammadBurhanDinDimashqi.BayanWaTacrif", "البيان والتعريف في أسباب ورود الحديث الشريف", "ابن حمزة الحسيني", 1120, "asbab", 2),
    ("0204Shafici.IkhtilafHadith", "اختلاف الحديث", "الشافعي", 204, "sharh", 3),
    ("0276IbnQutaybaDinawari.TawilMukhtalafHadith", "تأويل مختلف الحديث", "ابن قتيبة", 276, "sharh", 4),
    ("0321Tahawi.SharhMacaniAthar", "شرح معاني الآثار", "الطحاوي", 321, "athar", 5),
    ("0321Tahawi.SharhMushkilAthar", "شرح مشكل الآثار", "الطحاوي", 321, "sharh", 6),
    ("0406IbnFurakIsbahani.MushkilHadith", "مشكل الحديث وبيانه", "ابن فورك", 406, "sharh", 7),
    ("0388AbuSulaymanKhattabi.MacalimSunan", "معالم السنن (شرح سنن أبي داود)", "الخطابي", 388, "sharh", 10),
    ("0449IbnBattalQurtubi.SharhSahihBukhari", "شرح صحيح البخاري", "ابن بطال", 449, "sharh", 11),
    ("0463IbnCabdBarr.TamhidMuwatta", "التمهيد لما في الموطأ من المعاني والأسانيد", "ابن عبد البر", 463, "sharh", 12),
    ("0463IbnCabdBarr.IstidhkarJamic", "الاستذكار", "ابن عبد البر", 463, "sharh", 13),
    ("0510IbnMascudBaghawi.SharhSunna", "شرح السنة", "البغوي", 516, "sharh", 14),
    ("0544QadiCiyad.IkmalMuclim", "إكمال المعلم بفوائد مسلم", "القاضي عياض", 544, "sharh", 15),
    ("0597IbnJawzi.KashfMushkil", "كشف المشكل من حديث الصحيحين", "ابن الجوزي", 597, "sharh", 155),
    ("0656AbuCabbasAnsariQurtubi.Mufhim", "المفهم لما أشكل من تلخيص كتاب مسلم", "القرطبي (أبو العباس)", 656, "sharh", 16),
    ("0676Nawawi.MinhajFiSharhMuslim", "المنهاج شرح صحيح مسلم بن الحجاج", "النووي", 676, "sharh", 17),
    ("0795IbnRajabHanbali.FathBariFiSharhSahihBukhari", "فتح الباري شرح صحيح البخاري (ابن رجب)", "ابن رجب", 795, "sharh", 18),
    ("0795IbnRajabHanbali.JamicCulumWaHikam", "جامع العلوم والحكم", "ابن رجب", 795, "sharh", 19),
    ("0852IbnHajarCasqalani.FathBari", "فتح الباري شرح صحيح البخاري", "ابن حجر", 852, "sharh", 20),
    ("0855BadrDinCayni.CumdatQari", "عمدة القاري شرح صحيح البخاري", "العيني", 855, "sharh", 21),
    ("0923AhmadQastallani.IrshadSari", "إرشاد الساري لشرح صحيح البخاري", "القسطلاني", 923, "sharh", 22),
    ("1014MullaCaliQari.MirqatMafatih", "مرقاة المفاتيح شرح مشكاة المصابيح", "ملا علي القاري", 1014, "sharh", 23),
    ("1031CabdRaufMunawi.FaydQadir", "فيض القدير شرح الجامع الصغير", "المناوي", 1031, "sharh", 24),
    ("1182IbnIsmacilSancani.SubulSalam", "سبل السلام شرح بلوغ المرام", "الصنعاني", 1182, "sharh", 25),
    ("1255Shawkani.NaylAwtar", "نيل الأوطار", "الشوكاني", 1250, "sharh", 26),
    ("1329MuhammadAshrafCazimabadi.CawnMacbud", "عون المعبود شرح سنن أبي داود", "العظيم آبادي", 1329, "sharh", 27),
    ("1353IbnCabdRahimMubarakfuri.TuhfatAhwadhi", "تحفة الأحوذي شرح جامع الترمذي", "المباركفوري", 1353, "sharh", 28),
]
# الشروح المعاصرة (OCR): (بادئة الملف، العنوان، المؤلف، الوفاة)
OCR_BOOKS = [
    ("شرح_رياض_الصالحين", "شرح رياض الصالحين", "ابن عثيمين", 1421),
    ("شرح_الأربعين_النووية", "شرح الأربعين النووية", "ابن عثيمين", 1421),
    ("فتح_ذي_الجلال_والإكرام", "فتح ذي الجلال والإكرام بشرح بلوغ المرام", "ابن عثيمين", 1421),
    ("الحلل_الإبريزية", "الحلل الإبريزية من التعليقات البازية على صحيح البخاري", "ابن باز", 1420),
    ("توضيح_الأحكام", "توضيح الأحكام من بلوغ المرام", "عبد الله البسام", 1423),
    ("تيسير_العلام", "تيسير العلام شرح عمدة الأحكام", "عبد الله البسام", 1423),
    ("تطريز_رياض_الصالحين", "تطريز رياض الصالحين", "فيصل آل مبارك", 1376),
]

# سياق كل كتاب: من هو «المصنف/المؤلف» المشروح، وما الكُنى الخاصة (كنية الشارح نفسه أو من يكثر نقله)
BOOK_CTX = {
    "اختلاف الحديث": {"أبو عبد الله": "@author"},
    "شرح معاني الآثار": {"أبو جعفر": "@author", "أبو حنيفة": "أبو حنيفة"},
    "شرح مشكل الآثار": {"أبو جعفر": "@author"},
    "معالم السنن (شرح سنن أبي داود)": {"المصنف": "أبو داود", "المؤلف": "أبو داود", "أبو سليمان": "@author"},
    "شرح صحيح البخاري": {"المصنف": "البخاري", "المؤلف": "البخاري", "أبو عبد الله": "البخاري", "أبو الحسن": "@author"},
    "التمهيد لما في الموطأ من المعاني والأسانيد": {"المصنف": "مالك", "أبو عمر": "@author"},
    "الاستذكار": {"المصنف": "مالك", "أبو عمر": "@author"},
    "شرح السنة": {"أبو محمد": "@author"},
    "إكمال المعلم بفوائد مسلم": {"المصنف": "مسلم", "المؤلف": "مسلم", "القاضي": "@author", "الإمام": "المازري"},
    "المفهم لما أشكل من تلخيص كتاب مسلم": {"المصنف": "مسلم", "القاضي": "القاضي عياض", "أبو العباس": "@author"},
    "المنهاج شرح صحيح مسلم بن الحجاج": {"المصنف": "مسلم", "المؤلف": "مسلم", "القاضي": "القاضي عياض", "الإمام": "المازري"},
    "فتح الباري شرح صحيح البخاري (ابن رجب)": {"المصنف": "البخاري", "أبو عبد الله": "البخاري"},
    "جامع العلوم والحكم": {"المصنف": "النووي"},
    "فتح الباري شرح صحيح البخاري": {"المصنف": "البخاري", "المؤلف": "البخاري", "أبو عبد الله": "البخاري", "القاضي": "القاضي عياض", "الشيخ": "ابن دقيق العيد"},
    "عمدة القاري شرح صحيح البخاري": {"المصنف": "البخاري", "أبو عبد الله": "البخاري", "القاضي": "القاضي عياض"},
    "إرشاد الساري لشرح صحيح البخاري": {"المصنف": "البخاري", "أبو عبد الله": "البخاري", "الحافظ": "ابن حجر", "القاضي": "القاضي عياض"},
    "مرقاة المفاتيح شرح مشكاة المصابيح": {"المصنف": "التبريزي", "المؤلف": "التبريزي", "الحافظ": "ابن حجر", "الشيخ": "ابن حجر", "القاضي": "البيضاوي"},
    "فيض القدير شرح الجامع الصغير": {"المصنف": "السيوطي", "المؤلف": "السيوطي", "الحافظ": "ابن حجر"},
    "سبل السلام شرح بلوغ المرام": {"المصنف": "ابن حجر", "المؤلف": "ابن حجر", "الحافظ": "ابن حجر"},
    "نيل الأوطار": {"المصنف": "ابن تيمية (المجد)", "الحافظ": "ابن حجر"},
    "عون المعبود شرح سنن أبي داود": {"المصنف": "أبو داود", "المؤلف": "أبو داود", "الحافظ": "ابن حجر", "المنذري": "المنذري"},
    "تحفة الأحوذي شرح جامع الترمذي": {"المصنف": "الترمذي", "المؤلف": "الترمذي", "أبو عيسى": "الترمذي", "الحافظ": "ابن حجر"},
    "شرح رياض الصالحين": {"المصنف": "النووي", "المؤلف": "النووي", "الحافظ": "ابن حجر"},
    "شرح الأربعين النووية": {"المصنف": "النووي", "المؤلف": "النووي"},
    "فتح ذي الجلال والإكرام بشرح بلوغ المرام": {"المصنف": "ابن حجر", "المؤلف": "ابن حجر", "الحافظ": "ابن حجر"},
    "الحلل الإبريزية من التعليقات البازية على صحيح البخاري": {"المصنف": "البخاري", "الحافظ": "ابن حجر"},
    "توضيح الأحكام من بلوغ المرام": {"المصنف": "ابن حجر", "المؤلف": "ابن حجر", "الحافظ": "ابن حجر"},
    "تيسير العلام شرح عمدة الأحكام": {"المصنف": "عبد الغني المقدسي", "الحافظ": "ابن حجر"},
    "تطريز رياض الصالحين": {"المصنف": "النووي", "المؤلف": "النووي", "الحافظ": "ابن حجر"},
}
# كُنى الصحابة المشهورين التي تُقبل بمجرّدها من جدول الرواة
KNOWN_KUNYA = set("""أبو هريرة، أبو سعيد، أبو موسى، أبو ذر، أبو الدرداء، أبو بكر، أبو بكرة، أبو أمامة، أبو قتادة، أبو أيوب، أبو مسعود، أبو رافع، أبو طلحة، أبو جحيفة،
أبو برزة، أبو ثعلبة، أبو واقد، أبو حميد، أبو شريح، أبو سفيان، أبو عبيدة، أبو لبابة، أبو أسيد، أبو جهيم، أبو بردة، أبو عبس، أبو عمرة، أبو جندل، أبو الطفيل، أبو محذورة""".replace("\n", " ").split("،"))
KNOWN_KUNYA = set(k.strip() for k in KNOWN_KUNYA if k.strip())

# ---------- التصنيف الآلي التقريبي ----------
CATS = {
    "عقدية": "الايمان التوحيد الشرك القدر الصفات الرويه الشفاعه الجنه النار البعث القبر الملايكه النبوه الغيب البدعه الكفر النفاق الاشاعره المعتزله الخوارج الجهميه الاستواء العرش الاسماء والصفات القدريه المرجيه الايمان بالله الرب الالوهيه الربوبيه اليقين الاعتقاد عقيده السنه والجماعه التاويل الظاهر المتشابه القيامه الحشر الميزان الصراط الحوض عذاب القبر الروح الملك الشيطان الجن الدجال المهدي علامات الساعه",
    "فقهية": "يجب واجب حرام يحرم مستحب يستحب مكروه يكره مباح جواز يجوز لا يجوز الصلاه الزكاه الصوم الصيام الحج العمره الطهاره الوضوء الغسل التيمم النكاح الطلاق العده البيع الربا الميراث الحدود القصاص الجهاد الذبايح الاطعمه الايمان والنذور النذر المذهب الشافعي مالك ابو حنيفه احمد الجمهور اختلف العلماء مذهب الفقهاء فرض سنه ركن شرط باطل صحيح القضاء الشهاده الوقف الهبه الاجاره الرهن الدين القرض الكفاره الفديه الدم الاضحيه العقيقه الصيد اللباس الحرير الذهب المسح الخفين الجمعه العيد الجنازه الميت السفر القصر الجمع الاذان الامامه المسجد القبله السجود الركوع الفاتحه التشهد المكوس الخراج الغنيمه الفيء الجزيه الاسير",
    "اجتماعية وأخلاقية": "الاخلاق الادب الاداب الجار الرحم صله الرحم الوالدين بر الزوج الزوجه المراه الاولاد الولد الضيف الضيافه السلام الصدق الكذب الغيبه النميمه التواضع الكبر الحياء المجتمع الناس الصحبه الصاحب الاحسان الرفق الرحمه العفو الحلم الغضب الحسد البغض المحبه الاخوه الاخ المسلم حق المسلم الجماعه الامام السلطان الرعيه الطاعه الفتنه الظلم العدل الحاكم الامير النصيحه المعروف المنكر الامر بالمعروف التعامل المعامله الجيران الاقارب اليتيم المسكين الفقير الغني المال الكسب الرزق العمل الصناعه الزراعه التجاره الضحك المزاح الشعر اللعب الغناء",
    "تربوية وسلوكية": "القلب النيه الاخلاص الصبر الشكر التوبه الذكر الدعاء الزهد التقوي الخشوع الخوف الرجاء التوكل المراقبه المحاسبه الاستغفار الورع الرياء العجب النفس الهوي المجاهده التزكيه الطاعه المعصيه الذنب الذنوب الحسنات السييات الاجر الثواب العقاب الفضل فضيله ترغيب ترهيب الاستقامه التعليم العلم المتعلم المعلم الطالب التربيه الصغير الصبي الطفل",
    "لغوية وبيانية": "قوله معناه اي المعني اللغه يقال بفتح بكسر بضم بالتشديد بالتخفيف مشتق جمع مفرد الاصل في اللغه الاستعاره الكنايه المجاز البلاغه الاعراب النحو الضمير الفاعل المفعول الخطاب العرب لغه المراد به اراد التقدير المحذوف الحرف الحروف الهمزه الياء الواو",
    "حديثية (إسناد ورواية)": "الاسناد الراوي الرواه رواه اخرجه تفرد متابعه شاهد ثقه ضعيف مرسل طريق طرق الروايه روايه الرواية المتن السند علق التعليق انفرد وصله موصول منقطع مدرج الشيخان البخاري ومسلم اخرج الحديث في الصحيح التخريج المصنف الترجمه بوب الباب مطابقه الترجمه",
}
CAT_WORDS = {c: set(w for w in s.split()) for c, s in CATS.items()}
NAME_PAT = r"((?:(?:الإمام|الشيخ|الحافظ|القاضي)\s+)?(?:(?:ابن|أبو|أبي|أم|عبد)\s+)?[ء-ي]+(?:\s+(?:بن|ابن|بنت|أبي|أبو|عبد)\s+[ء-ي]+|\s+ال[ء-ي]{2,}){0,3})"
STATEMENT_RE = re.compile(r"(?:^|[\s،.؛:])(?:و?قال|فقال|وقالت|قالت)\s+" + NAME_PAT + r"\s*(?:رحمه الله|رضي الله عنه|رضي الله عنها|رضي الله عنهما|رحمه الله تعالى|رضي الله تعالى عنه)?\s*[:،]?\s*(.{25,700}?)(?=\.\s|\.$|؛|\n|$|\s(?:و?قال|فقال|وقيل|قلت|انتهى|اه)\s)")
AUTHOR_RE = re.compile(r"(?:^|[\s،.؛:])((?:وفي الحديث|وفي هذا الحديث|في الحديث|وفيه|ويستفاد منه|ويستفاد من الحديث|يستفاد منه|ويؤخذ منه|واستدل به|واستنبط منه|وفيه دليل|وفيه دلالة|وفي هذا دليل|ومن فوائد الحديث|ومن فوائده|فيه من الفوائد|ويدل على|وهذا يدل على|وهذا الحديث يدل على|وفيه جواز|وفيه استحباب|وفيه مشروعية|وفيه إثبات|وفيه الرد على|وفيه بيان|وفيه أن|وفيه إشارة)\s+.{20,600}?)(?=\.\s|\.$|؛|\n|$)")
# الصحابة والتابعون المشهورون (الاسم الشائع ← (الوفاة، الطبقة)) — لقبول آثارهم بصيغة الرواية بدقة، لا اعتمادًا على جدول الرواة المتشابه الأسماء
EARLY_NAMES = {}
for _names, _d, _t in [
    ("أبو بكر|أبو بكر الصديق", 13, 1), ("عمر|عمر بن الخطاب", 23, 1), ("عثمان|عثمان بن عفان", 35, 1), ("علي|علي بن أبي طالب", 40, 1),
    ("ابن عمر|عبد الله بن عمر", 73, 1), ("ابن عباس|عبد الله بن عباس", 68, 1), ("ابن مسعود|عبد الله بن مسعود", 32, 1), ("عائشة", 58, 1),
    ("أبو هريرة", 57, 1), ("أنس|أنس بن مالك", 93, 1), ("جابر|جابر بن عبد الله", 78, 1), ("أبو سعيد|أبو سعيد الخدري", 74, 1),
    ("معاذ|معاذ بن جبل", 18, 1), ("أبي بن كعب|أبي", 30, 1), ("زيد بن ثابت", 45, 1), ("ابن الزبير|عبد الله بن الزبير", 73, 1), ("أم سلمة", 62, 1),
    ("أبو الدرداء", 32, 1), ("حذيفة|حذيفة بن اليمان", 36, 1), ("سلمان|سلمان الفارسي", 36, 1), ("أبو ذر|أبو ذر الغفاري", 32, 1), ("أبو موسى|أبو موسى الأشعري", 44, 1),
    ("عمرو بن العاص", 43, 1), ("معاوية|معاوية بن أبي سفيان", 60, 1), ("ابن عمرو|عبد الله بن عمرو|عبد الله بن عمرو بن العاص", 65, 1), ("طلحة|طلحة بن عبيد الله", 36, 1),
    ("الزبير|الزبير بن العوام", 36, 1), ("سعد|سعد بن أبي وقاص", 55, 1), ("عبد الرحمن بن عوف", 32, 1), ("أبو عبيدة|أبو عبيدة بن الجراح", 18, 1),
    ("عمار|عمار بن ياسر", 37, 1), ("بلال", 20, 1), ("خباب|خباب بن الأرت", 37, 1), ("عبادة بن الصامت", 34, 1), ("أبو أيوب|أبو أيوب الأنصاري", 52, 1),
    ("أبو طلحة", 34, 1), ("أبو قتادة", 54, 1), ("أبو أمامة", 86, 1), ("أبو مسعود|أبو مسعود الأنصاري|أبو مسعود البدري", 40, 1), ("أبو بكرة", 52, 1),
    ("عمران بن حصين", 52, 1), ("سمرة|سمرة بن جندب", 58, 1), ("المغيرة|المغيرة بن شعبة", 50, 1), ("أبو برزة|أبو برزة الأسلمي", 64, 1), ("البراء|البراء بن عازب", 72, 1),
    ("سهل بن سعد", 91, 1), ("أبو جحيفة", 74, 1), ("رافع بن خديج", 74, 1), ("جابر بن سمرة", 74, 1), ("عبد الله بن جعفر", 80, 1), ("النعمان بن بشير", 65, 1),
    ("أسامة|أسامة بن زيد", 54, 1), ("زيد بن أرقم", 68, 1), ("حفصة", 45, 1), ("أم سلمة", 62, 1), ("أسماء|أسماء بنت أبي بكر", 73, 1), ("ميمونة", 51, 1),
    ("أم عطية", None, 1), ("فاطمة", 11, 1), ("خالد بن الوليد", 21, 1), ("أبو سفيان", 31, 1), ("عثمان بن أبي العاص", 51, 1), ("عقبة بن عامر", 58, 1),
    ("عبد الله بن سلام", 43, 1), ("كعب بن مالك", 50, 1), ("عبد الله بن أبي أوفى", 87, 1), ("واثلة بن الأسقع", 85, 1), ("أبو الطفيل", 100, 1),
    ("ابن أم مكتوم", 15, 1), ("عبد الله بن مغفل", 59, 1), ("أبو رافع", 40, 1), ("جرير|جرير بن عبد الله", 51, 1), ("عدي بن حاتم", 68, 1), ("قيس بن سعد", 60, 1),
    ("الحسن بن علي", 50, 1), ("الحسين|الحسين بن علي", 61, 1), ("أبو ثعلبة الخشني", 75, 1), ("عوف بن مالك", 73, 1), ("شداد بن أوس", 58, 1), ("أبو واقد الليثي", 68, 1),
    # التابعون
    ("سعيد بن المسيب|ابن المسيب", 94, 2), ("عروة|عروة بن الزبير", 94, 2), ("القاسم|القاسم بن محمد", 106, 2), ("سالم|سالم بن عبد الله", 106, 2),
    ("نافع", 117, 3), ("علقمة|علقمة بن قيس", 62, 2), ("الأسود|الأسود بن يزيد", 75, 2), ("مسروق", 63, 2), ("شريح|شريح القاضي", 78, 2), ("أبو وائل|شقيق بن سلمة", 82, 2),
    ("إبراهيم|إبراهيم النخعي|النخعي", 96, 3), ("الشعبي|عامر الشعبي", 103, 3), ("الحسن|الحسن البصري", 110, 3), ("ابن سيرين|محمد بن سيرين", 110, 3),
    ("عطاء|عطاء بن أبي رباح", 114, 3), ("طاوس|طاوس بن كيسان", 106, 3), ("مجاهد|مجاهد بن جبر", 104, 3), ("عكرمة", 105, 3), ("سعيد بن جبير", 95, 3),
    ("قتادة", 118, 4), ("الزهري|ابن شهاب|ابن شهاب الزهري", 124, 4), ("عمر بن عبد العزيز", 101, 3), ("مكحول", 113, 3), ("الحكم|الحكم بن عتيبة", 115, 3),
    ("حماد|حماد بن أبي سليمان", 120, 3), ("أبو الزناد", 130, 3), ("ربيعة|ربيعة الرأي", 136, 3), ("يحيى بن سعيد|يحيى بن سعيد الأنصاري", 143, 4),
    ("أبو الشعثاء|جابر بن زيد", 93, 3), ("عبيدة|عبيدة السلماني", 72, 2), ("زر بن حبيش|زر", 82, 2), ("محمد بن كعب|محمد بن كعب القرظي", 108, 3),
    ("خارجة بن زيد", 100, 3), ("أبو سلمة|أبو سلمة بن عبد الرحمن", 94, 3), ("عبيد الله بن عبد الله", 98, 3), ("سليمان بن يسار", 107, 3), ("عطاء بن يسار", 103, 3),
    ("عمرة|عمرة بنت عبد الرحمن", 98, 3), ("محمد بن الحنفية|ابن الحنفية", 81, 2), ("أبو العالية", 90, 2), ("الربيع بن خثيم", 63, 2), ("عامر بن شراحيل", 103, 3),
    ("محمد بن علي|الباقر", 114, 4), ("أبو جعفر", 114, 4), ("عبد الرحمن بن أبي ليلى", 83, 2), ("علي بن الحسين|زين العابدين", 94, 3), ("ميمون بن مهران", 117, 4),
    ("الضحاك|الضحاك بن مزاحم", 105, 4), ("أبو قلابة", 104, 3), ("بكر بن عبد الله|بكر المزني", 106, 3), ("أيوب|أيوب السختياني", 131, 5), ("عمرو بن دينار", 126, 4),
]:
    _canon = _names.split("|")[0]
    for _n in _names.split("|"):
        EARLY_NAMES[_n] = (_d, _t, _canon)
AMBIGUOUS_SINGLE = {"عبد الله", "مالك", "محمد", "عبد الرحمن", "سعيد", "يحيى", "سفيان", "أبو جعفر", "أيوب", "يحيى بن سعيد", "أبو سلمة", "محمد بن علي"}

# أثر بصيغة الرواية: «… عن ابن عمر أنه كان يقول: …» / «عن ابن عباس قال: …» (آخر راوٍ في الإسناد ثم فعل القول أو الفعل)
ATHAR_VERB = r"(?:كان(?:ت)?\s+(?:يقول|تقول|لا|إذا|يفعل|يأمر|ينهى|يكره|يرى|يصلي|يصوم|يقرأ|يجعل|يعطي|يقسم|يأخذ|يقوم|يجلس|يغتسل|يتوضأ|يمسح|يقنت|يرفع|يسلم|يكبر|يجهر|يخفي|يسجد|يركع|يدعو|يحلف|يبيع|يشتري|يكتب|يقضي|يفتي|يعلم|يعتق|يصلى|يطوف|يلبي|يحرم|يهل|يذبح|ينحر|يخرج|يدخل|يمشي|يركب|يأكل|يشرب|يلبس|ينام|يقعد|يخطب|يقص|يعد|يحب|يكره)|قال(?:ت)?|سئل(?:ت)?|أفتى|كره|رخص|نهى|أمر|قرأ|قنت|توضأ|اغتسل|تيمم|مسح|رأى|جعل|كتب|قضى|قسم|أجاز|أوصى|أعتق|قطع|جلد|رجم|صلى|سجد|طاف|أهل|لبى|ذبح|نحر|أحرم|أفطر|صام)"
ATHAR_RE = re.compile(r"عن\s+" + NAME_PAT + r"\s*(?:رضي الله عنه(?:ما|ا)?|رحمه الله)?\s*[،,]?\s*(?:أنه|أنها|أنهما)?\s*(" + ATHAR_VERB + r")\b\s*[:،]?\s*(.{12,600}?)(?=\.\s|\.$|؛|\n|$)")
# قطع متن الأثر عند بداية كلام آخر (قول شارح أو رواية أخرى) حتى لا يختلط كلام الصحابي بكلام من بعده
ATHAR_CUT = re.compile(r"\s(?:و?قال|فقال|وقالت|وعن|وروى|وروي|ورواه|وأخرج|وأخرجه|رواه|أخرجه|وهذا|فهذا|وهو|فيدل|ويدل|وفيه|قلت|انتهى|اه|وبه قال|وكذلك|وكذا|والحديث|والصحيح|والمشهور|وقيل|قيل|يعني|أي|والمعنى|وقد|ولهذا|فلهذا|وقوله|قوله|قال المصنف|قال المؤلف)\s")
def clean_athar(body):
    b = body.strip(" ،:«»\"'.")
    b = re.sub(r"^[.،:؛\s]+", "", b)
    m = ATHAR_CUT.search(b, 12)
    if m:
        b = b[:m.start()]
    b = b.strip(" ،:؛«»\"'.-")
    return b

MARFU_MARK = re.compile(r"رسول الله|النبي|صلى الله عليه|عليه السلام|عليه الصلاة|يا رسول")
ISNAD_RE = re.compile(r"^(?:و?حدثنا|و?حدثن[يى]|و?أخبرنا|و?أخبرن[يى]|ثنا|نا|أنا|أنبأنا|أخبرنا|سمعت|عن\s|قرأت على|حدثت)")
STORY_MARKERS = ["جاء رجل", "جاءت امرأة", "أتى رجل", "أتت امرأة", "أتى النبي", "أتيت النبي", "سأل", "سئل", "كنا مع", "كنا عند", "كنت مع", "كنت عند", "خرجنا", "بينما", "بينا", "لما", "فقلت يا رسول الله", "قلت يا رسول الله", "قالوا يا رسول الله", "نزلت", "فأنزل الله", "فنزلت", "في غزوة", "يوم", "فذكر", "ذكرت", "دخل", "دخلت", "خطب", "خطبنا", "قدم", "قدمنا", "بعث", "أرسل", "مر", "مررت", "جالس", "جلوس", "قعود"]


def classify(text):
    ws = set(norm(text).split())
    best, bs, total = "أخرى", 0, 0
    for c, vocab in CAT_WORDS.items():
        s = len(ws & vocab)
        total += s
        if s > bs:
            best, bs = c, s
    if bs == 0:
        return "أخرى", 0.0
    return best, round(bs / max(1, total), 2)


def load_early_scholars(corpus):
    """أسماء الصحابة والتابعين ووفياتهم من جدول الرواة (شهرة/اسم مطبَّع → وفاة)"""
    m = {}
    for name, shohra, konya, death, tabaka in corpus.execute("select name, shohra, konya, death_year, tabaka from rawis"):
        d = None
        try:
            d = int(re.sub(r"\D", "", death or "") or 0) or None
        except ValueError:
            d = None
        for k in (shohra, konya, name):
            if not k:
                continue
            key = norm(k)
            if len(key) < 4:
                continue
            if key not in m or (d and (m[key][0] is None or d < m[key][0])):
                m[key] = (d, tabaka)
    return m


class Extractor:
    def __init__(self, ix, early, death_map):
        self.ix, self.early, self.death_map = ix, early, death_map

    NOT_SPEAKERS = {"النبي", "رسول الله", "اللهم", "الله", "تعالى", "عز وجل", "رب", "ربنا", "الرجل", "الأعرابي", "القوم", "بعضهم", "الملك", "جبريل"}

    def speaker(self, raw, author, author_death, ctx=None):
        n = re.sub(r"\s+", " ", raw).strip()
        n = re.sub(r"^(?:لي|له|لها|لهم|لنا|لك)\s+", "", n)
        if n in self.NOT_SPEAKERS or n.startswith("رسول") or n.startswith("النبي"):
            return None, None
        ctx = ctx or {}
        if n in ctx:
            v = ctx[n]
            if v == "@author":
                return author, author_death
            return v, self.death_map.get(v)
        n = re.sub(r"^(?:الإمام|الشيخ)\s+", "", n)
        if n in ctx:
            v = ctx[n]
            return (author, author_death) if v == "@author" else (v, self.death_map.get(v))
        # الاسم قد يجرّ معه كلمة من الكلام («النووي الجمعُ…»): نجرّب الاسم كاملًا ثم أقصر فأقصر حتى نصيب اسمًا معروفًا
        words = n.split()
        c = None
        for k in range(len(words), 0, -1):
            cand = canon(" ".join(words[:k]))
            if cand and (cand in self.death_map or cand in ctx.values() or norm(cand) in self.early):
                c = cand; break
        if c is None:
            c = canon(n)
            if c is not None and len(c.split()) > 2 and c.split()[-1].startswith("ال") and c.split()[-1] not in ("الله",):
                c = " ".join(c.split()[:-1])   # نُسقط الكلمة الأخيرة المشكوك فيها
        if c is None:
            return None, None
        if c == "مالك بن أنس": c = "مالك"
        if len(c.split()) == 1 and c not in self.death_map and not c.endswith("ي"):
            return None, None
        d = self.death_map.get(c)
        if d is None:
            e = self.early.get(norm(c)) or self.early.get(norm(n))
            # لا نعتمد جدول الرواة إلا للأسماء المركّبة من الطبقات الأولى؛ والكُنى المجرّدة لا تُقبل إلا للمشهورين
            is_kunya = c.startswith("أبو ") and len(c.split()) == 2
            if e and e[1] is not None and e[1] <= 12 and len(c.split()) >= 2 and (not is_kunya or c in KNOWN_KUNYA):
                d = e[0]
        return c, d

    def link_paras(self, paras):
        """يعيد لكل فقرة {cluster: hits}، وعنقود المقطع الغالب مع درجة ثقة"""
        per_para = []
        total = defaultdict(int)
        for p in paras:
            ws = content_words(p)
            hits = defaultdict(set)
            for pos, idx in self.ix.scan(ws):
                cl = self.ix.cluster[idx]
                if cl:
                    hits[cl].add(pos)
            strong = {cl: len(h) for cl, h in hits.items() if len(h) >= 3}
            if len(strong) > 5:
                strong = dict(sorted(strong.items(), key=lambda x: -x[1])[:5])
            per_para.append(strong)
            for cl, h in hits.items():
                total[cl] += len(h)
        ranked = sorted(total.items(), key=lambda x: -x[1])
        dominant = None
        if ranked and ranked[0][1] >= 4:
            h1 = ranked[0][1]; h2 = ranked[1][1] if len(ranked) > 1 else 0
            conf = round(min(1.0, 0.45 + h1 / 20.0) * (0.6 + 0.4 * (1 - h2 / h1)), 2)
            dominant = (ranked[0][0], h1, conf)
        return per_para, dominant


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True); ap.add_argument("--ocr", required=True)
    ap.add_argument("--work", required=True); ap.add_argument("--corpus", required=True)
    ap.add_argument("--out", required=True); ap.add_argument("--only", default=None)
    a = ap.parse_args()
    t0 = time.time()
    ix = CorpusIndex(a.work)
    corpus = sqlite3.connect(a.corpus)
    early = load_early_scholars(corpus)
    sys.path.insert(0, os.path.dirname(__file__))
    from build_ahkam import DEATH
    death_map = dict(DEATH)
    death_map.update({b[2]: b[3] for b in BOOKS}); death_map.update({b[2]: b[3] for b in OCR_BOOKS})
    death_map.update({"أبو عبيد": 224, "أبو يوسف": 182, "محمد بن الحسن": 189, "زفر": 158, "ابن المسيب": 94, "ابن سيرين": 110, "الخليل": 170, "سيبويه": 180,
                      "الأصمعي": 216, "أبو عبيدة": 209, "الفراء": 207, "الزجاج": 311, "الأزهري": 370, "الجوهري": 393, "ابن الأنباري": 328, "ابن قتيبة": 276,
                      "ابن الأثير": 606, "الهروي": 401, "ابن دريد": 321, "الكسائي": 189, "ثعلب": 291, "المبرد": 285, "ابن فارس": 395, "أبو زيد": 215, "يونس": 182,
                      "الأخفش": 215, "ابن جني": 392, "ابن مالك": 672, "أبو حيان": 745, "ابن هشام": 761, "الزمخشري": 538, "الراغب": 502, "القرطبي": 671,
                      "البيضاوي": 685, "ابن عطية": 542, "أبو حنيفة": 150, "مالك بن أنس": 179, "الشافعي": 204, "أحمد": 241, "ابن راهويه": 238, "الطبراني": 360,
                      "ابن خزيمة": 311, "الدارقطني": 385, "الخطابي": 388, "ابن بطال": 449, "القاضي عياض": 544, "النووي": 676, "ابن حجر": 852, "العيني": 855,
                      "الكرماني": 786, "ابن التين": 611, "ابن المنير": 683, "الخطابي": 388, "المهلب": 435, "ابن أبي جمرة": 699, "ابن بزيزة": 662, "الطيبي": 743,
                      "ابن عبد السلام": 660, "العز بن عبد السلام": 660, "الغزالي": 505, "ابن حزم": 456, "الباجي": 474, "ابن رشد": 520, "ابن العربي": 543,
                      "المازري": 536, "الأبي": 827, "السنوسي": 895, "القسطلاني": 923, "زكريا الأنصاري": 926, "ابن حجر الهيتمي": 974, "الرملي": 1004,
                      "الشربيني": 977, "الخرشي": 1101, "الدردير": 1201, "الصاوي": 1241, "ابن عابدين": 1252, "الشوكاني": 1250, "الصنعاني": 1182, "المباركفوري": 1353,
                      "العظيم آبادي": 1329, "السندي": 1138, "ابن باز": 1420, "ابن عثيمين": 1421, "الألباني": 1420, "البسام": 1423, "عبد الله البسام": 1423,
                      "ابن سعدي": 1376, "السعدي": 1376, "الشنقيطي": 1393, "ابن إبراهيم": 1389, "الفوزان": None, "ابن الملك": 854, "التوربشتي": 661, "القاضي": 544, "أبو عمر": 463, "الإسماعيلي": 371, "ابن القصار": 397, "ابن القاسم": 191,
                      "الحافظ ابن حجر": 852, "الحافظ": None, "أبو عبد الله": None, "ابن أبي": None, "ابن عبد": None, "أبو عبد": None,
                      "ابن تيمية (المجد)": 652, "المجد": 652, "التبريزي": 741, "عبد الغني المقدسي": 600, "الأشرف": 1329, "المظهر": 727, "المظهري": 727,
                      "ابن حجر الهيتمي": 974, "الحافظ (كما سمّاه المؤلف)": None, "الطبري": 310, "الداودي": 402, "ابن التين": 611, "الزين بن المنير": 695,
                      "ابن المنير": 683, "الكرماني": 786, "البرماوي": 831, "الدماميني": 827, "السفاقسي": 743, "ابن أبي جمرة": 699, "ابن رشيد": 721,
                      "المنذري": 656, "ابن القيم": 751, "ابن سيد الناس": 734, "العلائي": 761, "الذهبي": 748, "ابن الصلاح": 643, "الرافعي": 623, "ابن قدامة": 620,
                      "الخرقي": 334, "أبو يعلى": 458, "ابن عقيل": 513, "ابن الجوزي": 597, "الموفق": 620, "ابن مفلح": 763, "المرداوي": 885, "البهوتي": 1051,
                      "ابن حزم": 456, "الطحاوي": 321, "الجصاص": 370, "الكاساني": 587, "السرخسي": 483, "ابن الهمام": 861, "ابن نجيم": 970, "ابن باز": 1420, "الكرماني": 786, "المهلب": 435, "الداودي": 402, "الطيبي": 743, "المازري": 536, "الباجي": 474,
                      "الأبي": 827, "السندي": 1138, "الطبري": 310, "الزهري": 124, "مجاهد": 104, "الحسن البصري": 110, "الحسن": 110, "قتادة": 118, "عكرمة": 105,
                      "سعيد بن جبير": 95, "سعيد بن المسيب": 94, "عطاء": 114, "طاوس": 106, "الشعبي": 103, "النخعي": 96, "إبراهيم النخعي": 96, "الأوزاعي": 157,
                      "الثوري": 161, "سفيان الثوري": 161, "ابن عيينة": 198, "الليث": 175, "ابن المبارك": 181, "إسحاق": 238, "أبو ثور": 240, "داود": 270,
                      "ابن جرير": 310, "ابن خزيمة": 311, "ابن المنذر": 318, "الطحاوي": 321, "ابن حبان": 354, "ابن عبد البر": 463, "الماوردي": 450, "الغزالي": 505,
                      "القاضي عياض": 544, "عياض": 544, "ابن العربي": 543, "ابن قدامة": 620, "ابن دقيق العيد": 702, "ابن تيمية": 728, "ابن القيم": 751, "الزركشي": 794,
                      "ابن الملقن": 804, "البلقيني": 805, "العراقي": 806, "ابن الجزري": 833, "السخاوي": 902, "السيوطي": 911, "الشوكاني": 1250, "الصنعاني": 1182,
                      "ابن عباس": 68, "ابن عمر": 73, "ابن مسعود": 32, "عائشة": 58, "أبو هريرة": 57, "أنس": 93, "جابر": 78, "أبو سعيد": 74, "علي": 40, "عمر": 23, "أبو بكر": 13, "عثمان": 35, "معاذ": 18, "أبي بن كعب": 30, "زيد بن ثابت": 45, "ابن الزبير": 73, "أم سلمة": 62, "أبو الدرداء": 32, "حذيفة": 36, "سلمان": 36, "أبو ذر": 32, "أبو موسى": 44, "عمرو بن العاص": 43, "معاوية": 60, "ابن عمرو": 65, "عبد الله بن عمرو": 65})
    ex = Extractor(ix, early, death_map)

    if os.path.exists(a.out):
        os.remove(a.out)
    db = sqlite3.connect(a.out)
    db.executescript("""
      PRAGMA page_size=4096;
      CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
      CREATE TABLE sh_books(id INTEGER PRIMARY KEY, title TEXT, author TEXT, death INTEGER, kind TEXT, ord INTEGER, edition TEXT, editor TEXT, publisher TEXT, vols INTEGER, sections INTEGER, words INTEGER);
      CREATE TABLE sh_sections(id INTEGER PRIMARY KEY, book_id INTEGER, ord INTEGER, title TEXT, vol INTEGER, page_start INTEGER, page_end INTEGER, words INTEGER, text BLOB);
      CREATE TABLE sh_links(section_id INTEGER, cluster_id INTEGER, hits INTEGER, conf REAL, para INTEGER);
      CREATE TABLE sh_points(id INTEGER PRIMARY KEY, cluster_id INTEGER, section_id INTEGER, book_id INTEGER, para INTEGER, scholar TEXT, death INTEGER,
                             tabaka INTEGER, category TEXT, cat_conf REAL, text TEXT, is_author INTEGER, conf REAL, kind TEXT DEFAULT 'qawl');
      CREATE TABLE stories(cluster_id INTEGER, bhid TEXT, hadith_id INTEGER, score INTEGER, words INTEGER);
    """)
    sec_id = 0
    book_id = 0
    stats = []

    def process_book(title, author, death, kind, ordv, meta, secs):
        nonlocal sec_id, book_id
        book_id += 1
        nwords = sum(len(s["text"].split()) for s in secs)
        db.execute("INSERT INTO sh_books VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                   (book_id, title, author, death, kind, ordv, meta.get("041.EdNUMBER"), meta.get("040.EdEDITOR"), meta.get("043.EdPUBLISHER"),
                    int(re.sub(r"\D", "", meta.get("022.BookVOLS", "") or "0") or 0), len(secs), nwords))
        nl = npts = 0
        for s in secs:
            sec_id += 1
            paras = [p for p in s["text"].split("\n") if p.strip()]
            db.execute("INSERT INTO sh_sections VALUES(?,?,?,?,?,?,?,?,?)",
                       (sec_id, book_id, s["ord"], (s.get("title") or "")[:120], s.get("vol"), s.get("page_start"), s.get("page_end"),
                        len(s["text"].split()), zlib.compress(s["text"].encode("utf-8"), 9)))
            per_para, dominant = ex.link_paras(paras)
            if dominant:
                cl, h, conf = dominant
                try:
                    db.execute("INSERT INTO sh_links VALUES(?,?,?,?,?)", (sec_id, int(cl), h, conf, -1)); nl += 1
                except ValueError:
                    dominant = None
            for pi, strong in enumerate(per_para):
                for cl, h in strong.items():
                    if dominant and cl == dominant[0]:
                        continue
                    try:
                        db.execute("INSERT INTO sh_links VALUES(?,?,?,?,?)", (sec_id, int(cl), h, round(min(1.0, 0.4 + h / 15.0), 2), pi)); nl += 1
                    except ValueError:
                        pass
            if kind == "asbab":
                continue
            # الاستنباطات
            count = 0
            nath = 0
            for pi, p in enumerate(paras):
                plain = strip_diac(p)
                # عنقود الفقرة: الأقوى في الفقرة، وإلا عنقود المقطع الغالب
                target, tconf = None, 0.0
                if per_para[pi]:
                    cl, h = max(per_para[pi].items(), key=lambda x: x[1]); target, tconf = cl, round(min(1.0, 0.4 + h / 15.0), 2)
                elif dominant:
                    target, tconf = dominant[0], dominant[2]
                if target is None:
                    continue
                try:
                    target_i = int(target)
                except ValueError:
                    continue
                seen = set()
                quoted_para = bool(per_para[pi]) and max(per_para[pi].values()) >= 6   # الفقرة نص الحديث نفسه لا شرحه
                for m in (STATEMENT_RE.finditer(plain) if not quoted_para else []):
                    sch, d = ex.speaker(m.group(1), author, death, BOOK_CTX.get(title))
                    if sch is None:
                        continue
                    txt = m.group(2).strip(" ،:")
                    key = norm(txt)[:60]
                    if key in seen or len(txt) < 25:
                        continue
                    if ISNAD_RE.match(txt) or txt.count(" بن ") >= 3 or "صلى الله عليه وسلم" in txt[:80] or "يا رسول الله" in txt:
                        continue
                    seen.add(key)
                    cat, cc = classify(txt)
                    tab = ex.early.get(norm(sch), (None, None))[1]
                    db.execute("INSERT INTO sh_points(cluster_id, section_id, book_id, para, scholar, death, tabaka, category, cat_conf, text, is_author, conf) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                               (target_i, sec_id, book_id, pi, sch, d, tab, cat, cc, txt, 0, tconf))
                    npts += 1; count += 1
                for m in AUTHOR_RE.finditer(plain):
                    txt = m.group(1).strip(" ،:")
                    key = norm(txt)[:60]
                    if key in seen or len(txt) < 25:
                        continue
                    seen.add(key)
                    cat, cc = classify(txt)
                    db.execute("INSERT INTO sh_points(cluster_id, section_id, book_id, para, scholar, death, tabaka, category, cat_conf, text, is_author, conf) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                               (target_i, sec_id, book_id, pi, author, death, None, cat, cc, txt, 1, tconf))
                    npts += 1; count += 1
                # آثار الصحابة والتابعين بصيغة الرواية (بلا ذكر النبي ﷺ في المتن كي لا تختلط بالمرفوع)
                for m in ATHAR_RE.finditer(plain):
                    raw_name = re.sub(r"\s+", " ", m.group(1)).strip()
                    raw_name = re.sub(r"^(?:الإمام|الشيخ|الحافظ|القاضي)\s+", "", raw_name)
                    e = EARLY_NAMES.get(raw_name)
                    if e is None:
                        sch0, _ = ex.speaker(raw_name, author, death, None)
                        e = EARLY_NAMES.get(sch0) if sch0 else None
                    if e is None or raw_name in AMBIGUOUS_SINGLE:
                        continue
                    d, tab, sch = e[0], e[1], e[2]
                    if tab > 4 or sch == author:
                        continue
                    verb, body = m.group(2), clean_athar(m.group(3))
                    txt = (verb + " " + body).strip()
                    if MARFU_MARK.search(txt) or len(body) < 15 or len(body.split()) < 4 or ISNAD_RE.match(body) or body.count(" بن ") >= 3:
                        continue
                    if ":" in body[:35] and ex.speaker(body.split(":")[0], author, death, None)[0] is not None:
                        continue   # «قال النسائي: …» — القائل غيره
                    if re.match(r"^(?:قال|قلت|قالت|و?حدثن|و?أخبرن|ثنا|أنا|سمعت|روى|رواه|أخرجه)", body):
                        continue   # نقل عن غيره أو إسناد
                    first = " ".join(body.split()[:3])
                    sp0 = ex.speaker(first, author, death, None)[0] or ex.speaker(" ".join(body.split()[:2]), author, death, None)[0]
                    if sp0 is not None and sp0 not in EARLY_NAMES:
                        continue   # يبدأ باسم عالم متأخر
                    key = norm(txt)[:60]
                    if key in seen:
                        continue
                    seen.add(key)
                    cat, cc = classify(txt)
                    db.execute("INSERT INTO sh_points(cluster_id, section_id, book_id, para, scholar, death, tabaka, category, cat_conf, text, is_author, conf, kind) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                               (target_i, sec_id, book_id, pi, sch, d, tab, cat, cc, txt, 0, tconf, "athar"))
                    npts += 1; count += 1; nath += 1
                if count > 60:
                    break
        db.commit()
        stats.append((title, len(secs), nl, npts))
        print(f"{title}: {len(secs)} مقطع، {nl} رابط، {npts} استنباط — {time.time()-t0:.0f}s", flush=True)

    # ---- كتب OpenITI ----
    for uri, title, author, death, kind, ordv in BOOKS:
        if a.only and a.only not in uri:
            continue
        path = find_file(a.data, uri)
        if not path:
            print("!! لم أجد", uri); continue
        secs = parse(path, max_words=450)
        process_book(title, author, death, kind, ordv, read_meta(path), secs)

    # ---- الشروح المعاصرة (OCR) ----
    from albani import parse_file, vol_of
    ocr_books = [(p_, t_, au_, d_, "modern") for p_, t_, au_, d_ in OCR_BOOKS]
    hf_meta = os.path.join(a.ocr, "hf_books.json")
    if os.path.exists(hf_meta):
        for p_, t_, au_, d_, k_ in json.load(open(hf_meta, encoding="utf-8")):
            if not any(p_ == x[0] for x in ocr_books):
                ocr_books.append((p_, t_, au_, d_, k_))
    for prefix, title, author, death, okind in ocr_books:
        if a.only and a.only not in prefix:
            continue
        if title not in death_map and death:
            death_map[author] = death
        files = sorted(f for f in glob.glob(os.path.join(a.ocr, prefix + "*")) if os.path.getsize(f) > 5000 and not re.search(r"\d+p\.txt$", f))
        if not files:
            print("!! لا ملفات لـ", title, "(نزّلها بـ fetch_hf.py)"); continue
        secs = []
        for f in files:
            vol = vol_of(os.path.basename(f)) if len(files) > 1 else None
            page = None; buf = []; words = 0; start = None; title_line = ""
            for pno, text in parse_file(f):
                page = pno if pno is not None else (page + 1 if page is not None else None)
                for line in text.split("\n"):
                    if not line.strip():
                        continue
                    if start is None:
                        start = page
                    if re.match(r"^\s*(?:باب|كتاب|فصل|الحديث\s+[ء-ي]+)\b", line) and words > 60:
                        secs.append({"ord": len(secs), "title": title_line, "vol": vol, "page_start": start, "page_end": page, "text": "\n".join(buf)})
                        buf, words, start, title_line = [], 0, page, line.strip()[:90]
                    buf.append(line.strip()); words += len(line.split())
                    if words > 450:
                        secs.append({"ord": len(secs), "title": title_line, "vol": vol, "page_start": start, "page_end": page, "text": "\n".join(buf)})
                        buf, words, start = [], 0, page
            if buf:
                secs.append({"ord": len(secs), "title": title_line, "vol": vol, "page_start": start, "page_end": page, "text": "\n".join(buf)})
        process_book(title, author, death, okind, death or 1400, {"040.EdEDITOR": None, "022.BookVOLS": str(len(files))}, secs)

    # ---- سياق الحديث وقصته من الروايات الثابتة في المتن ----
    print("بناء القصص من روايات المتن…", flush=True)
    nst = 0
    rows = corpus.execute("SELECT h.id, h.bhid, h.cluster_id, h.hokm, b.priority, h.matn FROM hadiths h JOIN books b ON b.id=h.book_id WHERE h.cluster_id IS NOT NULL AND b.priority <= 40").fetchall()
    by_cl = defaultdict(list)
    for hid, bhid, cl, hokm, prio, matn in rows:
        try:
            text = zlib.decompress(matn).decode("utf-8")
        except Exception:
            continue
        plain = strip_diac(text)
        nw = len(plain.split())
        score = sum(1 for mk in STORY_MARKERS if mk in plain)
        if score >= 2 and nw >= 25:
            # الثابت: من الصحيحين أو بحكم المحدِّث صحيح/حسن
            sahih = prio <= 1 or (hokm is not None and hokm <= 1)
            if sahih:
                by_cl[cl].append((score * 10 + min(nw, 300) // 10 + (5 if prio <= 1 else 0), hid, bhid, nw))
    for cl, lst in by_cl.items():
        lst.sort(reverse=True)
        for sc, hid, bhid, nw in lst[:3]:
            db.execute("INSERT INTO stories VALUES(?,?,?,?,?)", (cl, bhid, hid, sc, nw)); nst += 1
    db.executescript("""
      CREATE INDEX l_cl ON sh_links(cluster_id); CREATE INDEX l_sec ON sh_links(section_id);
      CREATE INDEX p_cl ON sh_points(cluster_id); CREATE INDEX s_book ON sh_sections(book_id, ord); CREATE INDEX st_cl ON stories(cluster_id);
    """)
    meta = {"schema_version": "2", "dataset": "shuruh", "built": time.strftime("%Y-%m-%d %H:%M"),  # بالوقت: بناءان في يوم واحد يُميَّزان عند التحديث التلقائي
            "athar_count": str(db.execute("select count(*) from sh_points where kind='athar'").fetchone()[0]),
            "books_count": str(book_id), "sections_count": str(sec_id),
            "points_count": str(db.execute("select count(*) from sh_points").fetchone()[0]),
            "links_count": str(db.execute("select count(*) from sh_links").fetchone()[0]),
            "stories_count": str(nst), "clusters_with_points": str(db.execute("select count(distinct cluster_id) from sh_points").fetchone()[0]),
            "scholars_count": str(db.execute("select count(distinct scholar) from sh_points").fetchone()[0])}
    for k, v in meta.items():
        db.execute("INSERT INTO meta VALUES(?,?)", (k, v))
    db.commit(); db.execute("VACUUM"); db.close()
    print(json.dumps(meta, ensure_ascii=False, indent=1))
    print(f"الحجم: {os.path.getsize(a.out)/1e6:.1f} م.ب — {time.time()-t0:.0f}s")


if __name__ == "__main__":
    main()
