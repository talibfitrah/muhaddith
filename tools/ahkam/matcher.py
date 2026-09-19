# -*- coding: utf-8 -*-
"""مطابقة نص خارجي بأحاديث المتن عبر فهرس الشظايا المبني بـ index_corpus.py"""
import os, sys
from collections import defaultdict
import numpy as np
sys.path.insert(0, os.path.dirname(__file__))
from common import content_words, shingles

N = 4

class CorpusIndex:
    def __init__(self, work):
        self.H = np.load(os.path.join(work, "sh_hash.npy"))
        self.I = np.load(os.path.join(work, "sh_hid.npy"))
        self.bhid, self.book, self.cluster, self.num, self.page, self.nw = [], [], [], [], [], []
        with open(os.path.join(work, "hadiths.tsv"), encoding="utf-8") as f:
            for line in f:
                p = line.rstrip("\n").split("\t")
                self.bhid.append(p[1]); self.book.append(p[2]); self.cluster.append(p[3])
                self.num.append(p[4]); self.page.append(p[5]); self.nw.append(int(p[6]))
        self.nw = np.array(self.nw)
        # تكرار كل شظية (لإهمال الصيغ الشائعة جدًّا)
        uniq, start, counts = np.unique(self.H, return_index=True, return_counts=True)
        self.uniq, self.start, self.counts = uniq, start, counts
        self.book_arr = np.array(self.book)

    def lookup(self, hsh):
        """يعيد مصفوفة أرقام الأحاديث التي فيها هذه الشظية (أو فارغة)."""
        k = np.searchsorted(self.uniq, hsh)
        if k < len(self.uniq) and self.uniq[k] == hsh:
            s = self.start[k]; n = self.counts[k]
            return self.I[s:s + n], int(n)
        return None, 0

    def match(self, text, book=None, max_df=400, min_hits=3):
        """أفضل الأحاديث المطابقة لنص. يعيد قائمة (idx, hits, ratio) مرتبة تنازليًّا."""
        ws = content_words(text)
        sh = shingles(ws, N)
        if not sh:
            return []
        hits = defaultdict(int)
        total = 0
        for hsh, _ in sh:
            ids, n = self.lookup(hsh)
            if ids is None or n > max_df:
                continue
            total += 1
            if book is not None:
                ids = ids[self.book_arr[ids] == book]
            for i in np.unique(ids):
                hits[int(i)] += 1
        out = []
        for i, h in hits.items():
            if h >= min_hits:
                denom = max(1, min(len(sh), max(1, self.nw[i] - N + 1)))
                out.append((i, h, h / denom))
        out.sort(key=lambda x: (-x[1], -x[2]))
        return out

    def scan(self, words_list, max_df=400):
        """يمسح كلمات مقطع طويل ويعيد قائمة (موضع الكلمة, idx) لكل شظية مطابقة (لربط المقاطع بالعناقيد)."""
        res = []
        for hsh, pos in shingles(words_list, N):
            ids, n = self.lookup(hsh)
            if ids is None or n > max_df:
                continue
            for i in np.unique(ids):
                res.append((pos, int(i)))
        return res
