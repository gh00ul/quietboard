"""Rebuilds app/src/main/assets/words_en.txt.

Sources:
  * AOSP LatinIME en_US word list (Apache-2.0) - spelling coverage
  * hermitdave/FrequencyWords en_50k, OpenSubtitles 2018 (CC-BY-SA-4.0) - modern conversational frequency
  * hand-curated texting slang and profanity (slurs are never included)
Output: word<TAB>freq[<TAB>flags]
  o = offensive (hidden when "Block offensive words" is on)
  s = texting slang/abbreviation (slightly handicapped as a glide result)
  n = known but never suggested: AOSP-flagged words outside the profanity roots (incl. slurs) are
      still real words a user may type ("sex", "naked"), so they must not be autocorrected away,
      but the keyboard never offers them.
"""
import math, re, sys, os
import numpy as np
here = os.path.dirname(os.path.abspath(__file__))
aosp = os.path.join(here, '.aosp_en_US.cache')      # LatinIME dictionaries/en_US_wordlist.combined (gunzipped)
subs = os.path.join(here, '.subtitles_en_50k.cache')  # FrequencyWords content/2018/en/en_50k.txt
out = os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'words_en.txt')
BLEND = '--no-blend' not in sys.argv
if '--out' in sys.argv: out = sys.argv[sys.argv.index('--out') + 1]
blend = 0.6
for i, arg in enumerate(sys.argv):
    if arg == '--out': out = sys.argv[i + 1]
    if arg == '--blend': blend = float(sys.argv[i + 1])

sub = {}
for line in open(subs, encoding='utf-8'):
    w, c = line.split(); sub[w] = int(c)

words, flagged, low = {}, [], {}
aosp_all = set()
for line in open(aosp, encoding='utf-8'):
    if not line.startswith(' word='): continue
    p = dict(x.split('=', 1) for x in line.strip().split(',') if '=' in x)
    w, f = p['word'], int(p['f'])
    # AOSP demotes some everyday words to near zero ("gay" 10, "ha" 0, "jam" 24). Undo that when
    # people demonstrably say the word; leave short demoted oddities ("ere", "op") demoted.
    # Short lowercase words stay demoted (they collide with typos: "ere" for "are"), bar a few.
    orig = int(p.get('originalFreq', f))
    if orig > f and sub.get(w.lower(), 0) >= 2000 and (len(w) >= 4 or not w.islower() or w in ('ha', 'aw', 'gay', 'sin', 'jam')):
        f = orig
    aosp_all.add(w)
    if p.get('not_a_word') == 'true': continue
    if p.get('possibly_offensive') == 'true': flagged.append((w, f)); continue
    if not re.fullmatch(r"[A-Za-z][A-Za-z']{0,23}", w) or w.endswith("'"): continue
    if f < 32: low[w] = max(f, low.get(w, 0)); continue  # maybe rescued below if people actually say it
    words[w] = max(f, words.get(w, 0))

# Profanity: only AOSP-flagged words built on these roots (slurs never match), boosted to texting levels.
roots = ['shit','fuck','damn','dammit','goddam','bitch','crap','piss','bastard','dick','boob','sexy','horny','bullsh']
off = {}
for w, f in flagged:
    lw = w.lower()
    if f < 20 or w[0].isupper(): continue
    if any(r in lw for r in roots) or re.search(r'(^|[a-z])ass(es|hole|holes)?$', lw):
        off[w] = min(200, f + 45)
off.update({'shit':170,'fuck':165,'fucking':160,'damn':160,'ass':145,'bitch':140,'bullshit':135,'fucked':135,'shitty':130,
            'asshole':130,'pissed':130,'dick':120,'crap':125,'goddamn':120,'dammit':120,'motherfucker':115,'sexy':130,'horny':110,
            'fuckin':125,'shitload':90,'badass':115,'dumbass':115,'jackass':100,'dipshit':90,'horseshit':85,'bitchin':80,
            'fml':110,'stfu':115,'wtf':150,'af':125,'asf':100,'smfh':90,'mf':90,'shitshow':95,'fuckboy':85,'lmfao':150})
for w in off: words.pop(w, None)

# Other flagged words: known (never "corrected"), never suggested. Lowercase only, like the profanity.
never = {}
for w, f in flagged:
    if w in off or w[0].isupper() or not re.fullmatch(r"[a-z][a-z']{0,23}", w) or w.endswith("'"): continue
    never[w] = max(f, 40)
for w in never: words.pop(w, None)

slang = dict(x.split() for x in """lol 195|lmao 185|omg 178|idk 178|tbh 152|ngl 142|smh 142|imo 136|imho 110|btw 162|brb 132|ttyl 122|gtg 122
|irl 122|fr 142|ong 110|bruh 152|bro 168|sis 125|fam 122|nah 162|yep 157|yup 152|nope 152|ya 152|yall 142|gonna 162|wanna 157|gotta 152
|kinda 152|sorta 122|lemme 132|gimme 127|dunno 132|tho 147|cuz 142|bc 122|rn 137|wyd 132|hbu 122|wbu 117|ikr 122|istg 100|thx 142|ty 142
|np 132|pls 152|plz 132|ok 190|u 152|ur 142|lowkey 132|highkey 100|deadass 110|sus 132|finna 110|tryna 122|slay 112|simp 100|stan 100
|yeet 100|rizz 100|bussin 90|homie 122|ugh 142|meh 122|hmm 147|hmmm 112|haha 172|hahaha 152|hehe 132|oof 122|welp 112|omw 147|ofc 132
|dm 132|dms 112|pic 142|pics 132|selfie 120|ig 122|insta 122|wifi 132|bday 132|convo 122|gf 132|bf 132|bff 122|hella 122
|legit 132|gotcha 122|aight 122|ight 112|sup 122|wassup 112|whatcha 102|prolly 112|probs 100|obvi 90|totes 90|cya 112|gn 112|gm 100
|hru 112|iykyk 90|fomo 102|gg 122|ez 102|yikes 130|vibe 130|vibes 125|lit 135|goat 125|bet 145|cap 130|mid 125|yo 150|dope 125
|chill 140|nvm 135|jk 140|ily 130|ilysm 110|tmrw 120|tmr 110|bae 110|xd 110|asap 140|fyi 130|thru 120|nite 110|luv 110|wat 100
|ppl 125|rly 120|srsly 105|idc 100|mhm 95|vid 95|vids 85""".replace('\n', '').split('|'))
slang = {k: int(v) for k, v in slang.items()}
for w, f in slang.items(): words[w] = max(words.get(w, 0), f)

# Rare-by-AOSP but common in speech ("jeez", "whew", "kiddo"): keep. Skip lowercase copies of names.
for w, f in low.items():
    is_name_copy = w.islower() and w.capitalize() in aosp_all
    too_short = len(w) < 4 and w.islower() and w not in ('aw',)  # "ere", "op", "co": typo magnets
    if sub.get(w.lower(), 0) >= 2000 and not is_name_copy and not too_short and w not in words:
        words[w] = max(f, 32)

# Everyday words AOSP (2014) lacks. Curated: the subtitle list alone is too noisy (OCR errors,
# foreign syllables, British spellings, slurs).
# Values are minimum frequencies. Brands keep their spelling ("youtube" -> "YouTube"); ones people
# type in lowercase need >= 110 so they're recased, not "corrected" (Lyft -> lift).
modern = {
    # everyday words AOSP lacks or under-ranks
    'congrats': 140, 'covid': 130, 'grey': 120, 'ew': 130, 'bitcoin': 110, 'crypto': 110, 'yea': 140,
    'podcast': 120, 'podcasts': 110, 'hashtag': 110, 'emoji': 125, 'emojis': 115, 'livestream': 100,
    'vape': 100, 'ghosted': 105, 'ghosting': 100, 'meme': 120, 'memes': 115, 'googled': 105,
    'texted': 120, 'texting': 120, 'unfollow': 100, 'selfies': 105, 'smartwatch': 100, 'smartphone': 105,
    'app': 150, 'apps': 135, 'online': 150, 'email': 150, 'emails': 130, 'website': 135, 'password': 135,
    'tweet': 110, 'tweets': 105, 'tweeted': 100, 'retweet': 100, 'retweeted': 90,
    'influencer': 100, 'influencers': 100, 'streamer': 100, 'streamers': 95, 'binge': 105, 'bingeing': 85,
    'unboxing': 80, 'cringe': 115, 'cringy': 105, 'bestie': 115, 'besties': 100, 'situationship': 90,
    'doomscrolling': 85, 'gaslight': 95, 'gaslighting': 105, 'delulu': 90, 'goated': 95, 'ick': 100,
    'vibing': 105, 'vibey': 90, 'vaxxed': 85,
    # texting abbreviations
    'lmk': 125, 'hmu': 115, 'wya': 110, 'wfh': 105, 'yolo': 110, 'afk': 95, 'tbf': 105, 'rofl': 100,
    'smth': 110, 'fwiw': 95, 'tldr': 100,
    # brands and apps, in their own spelling
    'YouTube': 125, 'YouTuber': 110, 'YouTubers': 100, 'TikTok': 125, 'TikToker': 110, 'Instagram': 125,
    'Snapchat': 115, 'WhatsApp': 125, 'Spotify': 115, 'Netflix': 120, 'Venmo': 110, 'Uber': 125,
    'Lyft': 110, 'Airbnb': 110, 'AirPods': 110, 'iPhone': 125, 'iPhones': 110, 'iPad': 110, 'iOS': 110,
    'Bluetooth': 110, 'Reddit': 110, 'ChatGPT': 110, 'DoorDash': 110, 'Fortnite': 110, 'Minecraft': 110,
    'Roblox': 110, 'Pinterest': 110, 'PlayStation': 110, 'Xbox': 110, 'Costco': 110, 'Walmart': 110,
    'Tesla': 110, 'LinkedIn': 110, 'FaceTime': 115, 'Android': 110,
    # contractions with an apostrophe ending: the subtitle list lost those ("m" is 46k for "I'm"),
    # so they'd stay at AOSP's book-text levels; in texting they're among the most common words
    "I'm": 190, "it's": 185, "that's": 175, "can't": 175, "I'll": 172, "you're": 170, "I've": 165,
    "let's": 165, "won't": 165, "what's": 160, "I'd": 158, "there's": 155, "he's": 155, "we're": 155,
    "they're": 155, "she's": 150, "you'll": 140, "we'll": 140, "you've": 140, "we've": 138, "they'll": 135,
    # ones AOSP lacks entirely, which autocorrect then "fixed" into other words (where'd -> where's)
    "c'mon": 120, "where'd": 110, "what'd": 110, "why'd": 105, "what're": 105, "when'd": 90,
    "there're": 90, "this'll": 90, "why's": 85, "why'll": 75,
    # web words typed in ordinary text ("https:" became "http:")
    'https': 100, 'iCloud': 100, 'Hotmail': 95,
}
present = {w.lower() for w in words} | {w.lower() for w in off} | set(never)
for w, f in modern.items():
    if w in words: words[w] = max(words[w], f)       # same spelling already there: just rank it up
    elif w.lower() not in present: words[w] = f      # new word
    elif w != w.lower() and w.lower() in words and words[w.lower()] < f:
        # only a lowercase copy exists (AOSP "facetime"): the brand spelling replaces it
        del words[w.lower()]; words[w] = f

rows = [[w, f, ''] for w, f in words.items()] + [[w, f, 'o'] for w, f in off.items()] + \
    [[w, f, 'n'] for w, f in never.items()]

# Blend in modern conversational frequency (scale-matched, not regression-compressed).
pref = {}
for i, (w, f, o) in enumerate(rows):
    k = w.lower()
    if k not in pref or w == k: pref[k] = i
xs = np.array([math.log(sub[k]) for k, i in pref.items() if k in sub and sub[k] >= 50 and rows[i][1] >= 60])
ys = np.array([rows[i][1] for k, i in pref.items() if k in sub and sub[k] >= 50 and rows[i][1] >= 60])
a = ys.std() / xs.std(); b = ys.mean() - a * xs.mean()
print("scale: f = %.2f*ln(count) %+.1f" % (a, b))
# Subtitle tokens split contractions ("haven't" -> "haven" + "t"), inflating the stems: don't blend them.
stems = {'haven', 'ain', 'isn', 'didn', 'doesn', 'wasn', 'weren', 'couldn', 'wouldn', 'shouldn', 'hasn',
         'hadn', 'aren', 'mustn', 'needn', 'don'}
for k, i in (pref.items() if BLEND else []):
    if k in stems: continue
    if k in sub and sub[k] >= 20:
        fs = min(250, max(0, a * math.log(sub[k]) + b))
        rows[i][1] = int(round((1 - blend) * rows[i][1] + blend * fs))
exact = {r[0]: i for i, r in enumerate(rows)}
for k, v in slang.items():   # curated slang keeps at least (roughly) its hand-set level
    if k in exact: rows[exact[k]][1] = max(rows[exact[k]][1], v - 8)
for k, v in modern.items():  # curated modern words and brands keep exactly their minimum
    if k in exact: rows[exact[k]][1] = max(rows[exact[k]][1], v)

# Contractions: the subtitle list splits them ("don't" -> "don" + "t", "I'm" -> "i" + "m"), so the
# stems' counts are really the contractions' counts. Rank the contractions by them.
contractions = {'don': "don't", 'didn': "didn't", 'doesn': "doesn't", 'isn': "isn't", 'wasn': "wasn't",
                'aren': "aren't", 'weren': "weren't", 'couldn': "couldn't", 'wouldn': "wouldn't",
                'shouldn': "shouldn't", 'hasn': "hasn't", 'haven': "haven't", 'hadn': "hadn't",
                'ain': "ain't"}
for stem, c in contractions.items():
    if c in exact and stem in sub:
        fs = min(250, a * math.log(sub[stem]) + b)
        r = rows[exact[c]]
        r[1] = max(r[1], int(round((1 - blend) * r[1] + blend * fs)))

# Anything built on a profanity root is offensive (hidden by "Block offensive words"), whatever AOSP
# says ("shittiest", "bitch's"); a few hateful words are never suggested at all.
# Only roots that can't occur inside ordinary words ("crap" is in "scrap", "ass" in "class").
hateful = {'kkk', 'fuhrer'}
strong = ('shit', 'fuck', 'bitch', 'bullsh')
for r in rows:
    lw = r[0].lower()
    if lw in hateful: r[2] = 'n'
    elif r[0].islower() and 'n' not in r[2] and 'o' not in r[2] and any(x in lw for x in strong):  # not names
        r[2] += 'o'
for k in ('shit', 'fuck', 'damn', 'wtf', 'lmfao'):
    if k in pref: rows[pref[k]][1] = max(rows[pref[k]][1], off.get(k, 0) - 8)

# 's' = texting slang/abbreviation: suggested normally, slightly handicapped as a glide result
abbrev = set(slang) | {'lmfao','wtf','af','asf','fml','stfu','smfh','mf'}
for r in rows:
    if r[0] in abbrev: r[2] += 's'
rows.sort(key=lambda r: (r[0].lower(), r[0]))
with open(out, 'w', encoding='utf-8', newline='\n') as fo:  # LF on every OS (the asset is LF)
    for w, f, o in rows: fo.write(f"{w}\t{f}\t{o}\n" if o else f"{w}\t{f}\n")
d = {r[0]: r[1] for r in rows}
print(len(rows), "words;", {w: d.get(w) for w in ['typing','topping','she','edge','the','when','stern','especially','worriedly','lol','shit','sir']})
